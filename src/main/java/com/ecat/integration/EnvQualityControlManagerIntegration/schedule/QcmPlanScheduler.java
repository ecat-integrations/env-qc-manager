package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 质控计划精确唤醒调度器（FR-01-02）：单线程按「最早 next_fire_time」挂一次性唤醒，
 * 到点触发 → 推进状态（next/last/FINISHED）→ rearm 挂下一次；无候选计划时不挂任何任务
 * （空转零开销，非每秒 beat）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>触发动作经 {@link PlanFireAction} 隔离（2.3 编排器接线缝）；无实现 bean 时每次 fire
 *       log.warn 提示「编排器未接线」，不静默假装执行成功，但状态机照常推进。</li>
 *   <li>misfire（FR-01-05/D1）：fire 时 next_fire_time 落后 now 超 60s 视为停机错过，
 *       不调 action、log.warn、仅重算 next。start 时先全量扫 ACTIVE 行把 past-due 的按 misfire 收口。</li>
 *   <li>漂移巡检（FR-01-07）：30s 固定率任务比对 DB 最早行与内存已挂计划，不一致 warn + rearm；
 *       防御性兜底，不承担触发。</li>
 *   <li>双触发防护（归属清理 + 指纹闩）：应用场景是并发 notify 与到点 fire 的竞态、
 *       以及 advancePlan 写 DB 失败后的重入。归属清理——onFire 只在 armedFuture 仍是
 *       本次唤醒自己的句柄时才清空（构造 runnable 时捕获引用），防止抹掉 notify/rearm
 *       刚挂的新任务句柄产生孤儿任务；指纹闩——fireInternal 以 planId+due 为指纹入闩，
 *       同指纹在 {@link #FINGERPRINT_WINDOW} 内二次进入（孤儿任务到点、或写失败 rearm
 *       读到旧 next_fire_time 立即重跑）只 log.error + 跳过 action、仍推进状态机，
 *       保证 action 语义上恰好执行一次；写失败热循环因此收敛为 drift audit 节奏重试。</li>
 *   <li>生命周期对齐 ASM 调度器先例：synchronized start() 幂等；shutdown() 由 @PreDestroy 与
 *       集成入口 onPause/onRelease 调（复位 started，pause→resume 可重启）；执行域经
 *       {@link QcmScheduleExecutor} 解析（生产=本仓自持 IO 域计时器——fire/rearm/driftAudit
 *       任务体走 planMapper PostgreSQL 阻塞 IO，按业务池边界 IO 禁入，不收编 core；ownsExecutor
 *       恒 true，由本类 shutdown() 经 shutdownPool() 关停并清缓存；注入的测试 executor 不关）。</li>
 * </ul>
 *
 * @author coffee
 */
@Service
public class QcmPlanScheduler {

    /** misfire 判定阈值：next_fire_time 落后 now 超过该时长视为停机错过（不追补）。 */
    static final Duration MISFIRE_THRESHOLD = Duration.ofSeconds(60);
    /** 漂移巡检周期（固定率）。 */
    static final long DRIFT_AUDIT_PERIOD_SECONDS = 30;
    /** 调度器落库的更新人（系统操作，非 ruoyi 用户）。 */
    static final String SCHEDULER_ACTOR = "qcm-scheduler";
    /** 指纹闩窗口：同指纹（planId+due）在该窗口内二次进入视为重复触发，跳过 action 仅推进。 */
    static final Duration FINGERPRINT_WINDOW = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(QcmPlanScheduler.class);

    private final QcmPlanMapper planMapper;
    private final ObjectProvider<PlanFireAction> fireActionProvider;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;

    /** 已挂唤醒（null = 当前无候选计划，空转）。armedPlanId/armedFireTime 供巡检比对与 notifyPlanChanged 定向取消。 */
    private ScheduledFuture<?> armedFuture;
    private Long armedPlanId;
    private Instant armedFireTime;
    private ScheduledFuture<?> driftFuture;

    private boolean started = false;
    private volatile boolean stopped = false;

    /** 指纹闩：key=planId+":"+due.toEpochMilli()，value=入闩时刻；过期条目在 rearm/drift audit 时顺带清理。 */
    private final Map<String, Instant> firedFingerprints = new HashMap<>();

    /**
     * 生产构造：@Service 注入 mapper + 编排器 provider（可缺省），系统时钟；执行域经
     * {@link QcmScheduleExecutor#resolve()} 收编到平台调度引擎车道（core 引擎 → 本地兜底，
     * 无 core 上下文时 ownsExecutor=true 由本类关停）。
     */
    @Autowired
    public QcmPlanScheduler(QcmPlanMapper planMapper, ObjectProvider<PlanFireAction> fireActionProvider) {
        this(planMapper, fireActionProvider, Clock.systemDefaultZone(),
                QcmScheduleExecutor.resolve());
    }

    private QcmPlanScheduler(QcmPlanMapper planMapper, ObjectProvider<PlanFireAction> fireActionProvider,
                             Clock clock, QcmScheduleExecutor.Resolution resolution) {
        this(planMapper, fireActionProvider, clock, resolution.executor(), resolution.ownsExecutor());
    }

    /** 测试构造：注入可控 Clock 与捕获型 executor（手动 run 挂起的任务），不拥有 executor。 */
    QcmPlanScheduler(QcmPlanMapper planMapper, ObjectProvider<PlanFireAction> fireActionProvider,
                     Clock clock, ScheduledExecutorService executor) {
        this(planMapper, fireActionProvider, clock, executor, false);
    }

    private QcmPlanScheduler(QcmPlanMapper planMapper, ObjectProvider<PlanFireAction> fireActionProvider,
                             Clock clock, ScheduledExecutorService executor, boolean ownsExecutor) {
        this.planMapper = planMapper;
        this.fireActionProvider = fireActionProvider;
        this.clock = clock;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * 启动调度（集成入口 onStart 调一次）；幂等（started 标志）。
     * 先做 misfire 扫描（载入全部 ACTIVE 行，past-due 的按 misfire 收口），再 rearm，最后挂漂移巡检。
     */
    public synchronized void start() {
        if (started) {
            log.debug("qcm 计划调度器已启动过，跳过重复启动");
            return;
        }
        started = true;
        stopped = false;
        scanMisfiresOnStart();
        rearmLocked();
        driftFuture = executor.scheduleAtFixedRate(this::driftAuditTick,
                DRIFT_AUDIT_PERIOD_SECONDS, DRIFT_AUDIT_PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("qcm 计划调度器已启动: armedPlanId={}", armedPlanId);
    }

    /**
     * 唤醒任务体（executor 线程执行）：归属清理 → fire → finally rearm 保证连续（tick 必收口）。
     * 归属清理：仅当 armedFuture 仍是本次唤醒的句柄（构造 runnable 时捕获）才清空；
     * 若已被 notifyPlanChanged/rearm 换成新句柄则不动，防止抹掉新任务句柄产生孤儿。
     */
    void onFire(Long planId, ScheduledFuture<?> wokenFuture) {
        if (stopped) {
            return;
        }
        synchronized (this) {
            if (armedFuture == wokenFuture) {
                armedFuture = null;
                armedPlanId = null;
                armedFireTime = null;
            }
        }
        try {
            fireInternal(planId);
        } catch (RuntimeException e) {
            log.error("[诊断调试] qcm 计划 {} fire 处理失败，rearm 续跑: ", planId, e);
        } finally {
            synchronized (this) {
                if (!stopped) {
                    rearmLocked();
                }
            }
        }
    }

    /** 单计划 fire：misfire 跳过 / 指纹闩查重 / 调编排器 / 推进 next、last、ONCE 收口。
     * 包级可见：测试直接构造同指纹二次进入场景（双触发竞态防护验证）。 */
    void fireInternal(Long planId) {
        QcmPlan plan = planMapper.selectById(planId);
        if (plan == null || !"ACTIVE".equals(plan.getStatus()) || plan.getNextFireTime() == null) {
            // 计划已删/停/手动改排，本轮无事可做（外层 rearm 重扫）
            return;
        }
        Instant now = clock.instant();
        Instant due = plan.getNextFireTime();
        if (Duration.between(due, now).compareTo(MISFIRE_THRESHOLD) > 0) {
            log.warn("[诊断调试] qcm 计划 {}({}) 错过触发时刻 {}（停机 misfire），跳过执行仅重算",
                    planId, plan.getPlanName(), due);
        } else if (!tryLatchFingerprint(planId, due)) {
            // 指纹闩拦截：孤儿任务与首次执行同指纹 / 写失败 rearm 立即重跑同指纹——action 只执行一次
            log.error("[诊断调试] qcm 计划 {}({}) 到点 {} 疑似重复触发（指纹闩窗口 {} 内二次进入），跳过 action 仅推进状态",
                    planId, plan.getPlanName(), due, FINGERPRINT_WINDOW);
        } else {
            PlanFireAction action = fireActionProvider == null ? null : fireActionProvider.getIfAvailable();
            if (action == null) {
                // 编排器（2.3）未接线：不静默假装执行成功，每次 fire 都 warn，状态机照常推进
                log.warn("[诊断调试] qcm 计划 {}({}) 到点 {} 但编排器未接线（PlanFireAction 无实现），跳过执行仅推进状态",
                        planId, plan.getPlanName(), due);
            } else {
                action.fire(planId);
            }
        }
        advancePlan(plan, now);
    }

    /**
     * 指纹闩（幂等）：planId+due 首次进入则入闩返回 true；窗口内二次进入返回 false（重复触发）。
     * 顺带淘汰过期条目（5 分钟），防闩无界增长。
     */
    private synchronized boolean tryLatchFingerprint(Long planId, Instant due) {
        evictExpiredFingerprintsLocked();
        String key = planId + ":" + due.toEpochMilli();
        Instant latchedAt = firedFingerprints.get(key);
        Instant now = clock.instant();
        if (latchedAt != null && Duration.between(latchedAt, now).compareTo(FINGERPRINT_WINDOW) < 0) {
            return false;
        }
        firedFingerprints.put(key, now);
        return true;
    }

    /** 淘汰超过指纹闩窗口的条目。调用方持锁（rearm / drift audit / 查闩时顺带清理）。 */
    private void evictExpiredFingerprintsLocked() {
        Instant now = clock.instant();
        firedFingerprints.entrySet().removeIf(
                e -> Duration.between(e.getValue(), now).compareTo(FINGERPRINT_WINDOW) >= 0);
    }

    /** 触发后推进状态：last=now；ONCE 置 FINISHED（不再算 next）；其余重算 next（窗口尽则置空）。 */
    private void advancePlan(QcmPlan plan, Instant now) {
        ScheduleSpec spec = ScheduleSpecs.fromConfig(plan.getScheduleType(), plan.getScheduleConfig(),
                plan.getPlanStartTime(), plan.getPlanEndTime());
        if (spec.getType() == ScheduleType.ONCE) {
            planMapper.updateNextFireTime(plan.getId(), null, now);
            planMapper.updateStatus(plan.getId(), "FINISHED", SCHEDULER_ACTOR);
            return;
        }
        Optional<Instant> next = ScheduleCalculator.nextFire(spec, now, clock.getZone());
        if (!next.isPresent()) {
            log.info("[诊断调试] qcm 计划 {}({}) 有效期窗口已尽，next_fire_time 置空（此后不再触发）",
                    plan.getId(), plan.getPlanName());
        }
        planMapper.updateNextFireTime(plan.getId(), next.orElse(null), now);
    }

    /**
     * 配置变更联动（FR-01-08）：service 在计划增/改/启/停/删后调——取消该计划已挂唤醒并 rearm。
     */
    public synchronized void notifyPlanChanged(Long planId) {
        if (!started || stopped) {
            return;
        }
        if (armedPlanId != null && armedPlanId.equals(planId)) {
            cancelArmedLocked();
        }
        rearmLocked();
    }

    /** 漂移巡检 task 体：必 catch 保固定率调度连续（task 抛未捕获异常则后续全抑制）。 */
    private void driftAuditTick() {
        try {
            runDriftAudit();
        } catch (RuntimeException e) {
            log.error("[诊断调试] qcm 计划漂移巡检失败，已吞保巡检连续: ", e);
        }
    }

    /**
     * 漂移巡检（FR-01-07）：比对 DB 最早 next_fire_time 行与内存已挂计划，不一致（漂移/漏挂）
     * 则 warn + rearm。包级可见：测试与 task 同入口确定性验证。
     */
    synchronized void runDriftAudit() {
        if (!started || stopped) {
            return;
        }
        QcmPlan earliest = planMapper.selectEarliestActive();
        boolean mismatch = (earliest == null) != (armedPlanId == null);
        if (earliest != null && armedPlanId != null) {
            mismatch = !earliest.getId().equals(armedPlanId)
                    || earliest.getNextFireTime() == null
                    || !earliest.getNextFireTime().equals(armedFireTime);
        }
        if (mismatch) {
            log.warn("[诊断调试] qcm 计划漂移：DB 最早=({},{}) vs 内存已挂=({},{})，rearm 校正",
                    earliest == null ? null : earliest.getId(),
                    earliest == null ? null : earliest.getNextFireTime(),
                    armedPlanId, armedFireTime);
            rearmLocked();
        }
    }

    /** 挂最早 ACTIVE 计划的一次性唤醒；无候选不挂（空转零开销）。调用方持锁。 */
    private void rearmLocked() {
        cancelArmedLocked();
        evictExpiredFingerprintsLocked();
        QcmPlan earliest = planMapper.selectEarliestActive();
        if (earliest == null || earliest.getNextFireTime() == null) {
            return;
        }
        long delayMs = Duration.between(clock.instant(), earliest.getNextFireTime()).toMillis();
        Long planId = earliest.getId();
        armedPlanId = planId;
        armedFireTime = earliest.getNextFireTime();
        // 数组桥接：runnable 内引用自己 schedule 返回的 future 句柄（onFire 归属清理的比对基准）
        final ScheduledFuture<?>[] handle = new ScheduledFuture<?>[1];
        handle[0] = executor.schedule(() -> onFire(planId, handle[0]), delayMs, TimeUnit.MILLISECONDS);
        armedFuture = handle[0];
    }

    private void cancelArmedLocked() {
        if (armedFuture != null) {
            armedFuture.cancel(false);
            armedFuture = null;
        }
        armedPlanId = null;
        armedFireTime = null;
    }

    /** start 时的停机 misfire 扫描：ACTIVE 且 past-due 超 60s 的行按 misfire 收口（跳过执行仅重算）。 */
    private void scanMisfiresOnStart() {
        QcmPlan filter = new QcmPlan();
        filter.setStatus("ACTIVE");
        List<QcmPlan> activePlans = planMapper.selectList(filter);
        Instant now = clock.instant();
        for (QcmPlan plan : activePlans) {
            if (plan.getNextFireTime() == null
                    || Duration.between(plan.getNextFireTime(), now).compareTo(MISFIRE_THRESHOLD) <= 0) {
                continue;
            }
            log.warn("[诊断调试] qcm 计划 {}({}) 停机期间错过触发时刻 {}，启动扫描按 misfire 跳过仅重算",
                    plan.getId(), plan.getPlanName(), plan.getNextFireTime());
            try {
                advancePlan(plan, now);
            } catch (RuntimeException e) {
                log.error("[诊断调试] qcm 计划 {} 启动 misfire 收口失败，跳过该行继续: ", plan.getId(), e);
            }
        }
    }

    /** 模块暂停/卸载释放（@PreDestroy + 入口 onPause/onRelease 调）：取消已挂任务并复位 started（可重启）。 */
    @PreDestroy
    public synchronized void shutdown() {
        stopped = true;
        started = false;
        cancelArmedLocked();
        if (driftFuture != null) {
            driftFuture.cancel(false);
            driftFuture = null;
        }
        if (ownsExecutor) {
            // 停池 + 清 QcmScheduleExecutor.LOCAL 缓存一起收口（直接 shutdownNow 会留 LOCAL
            // 指向死池，同 classloader 内再 resolve 全量 REE——动态重载/pause→resume 接线即触发）
            QcmScheduleExecutor.shutdownPool();
        }
        log.info("qcm 计划调度器已关闭");
    }
}
