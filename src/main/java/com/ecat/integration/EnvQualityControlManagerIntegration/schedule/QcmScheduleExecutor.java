package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Mdc.MdcScheduledExecutorService;

/**
 * qcm 计划调度器执行域解析：{@link QcmPlanScheduler} 的定时承载 = 本仓自持 IO 域计时器
 * （单线程 daemon {@code qcm-plan-sched}）。不再解析到 core 调度引擎——fire/rearm/
 * driftAudit 任务体经 planMapper 走 PostgreSQL（阻塞 DB IO），按业务池边界 IO 禁入
 * （ecat-biz-sched 2 线程小池会被 DB 等待饿死全部业务计时），core 引擎的 SES 契约面
 * 亦退役（仅剩 TaskManager 内部消费）；DB 型计时由本仓自建小池承载，故障域隔离。
 *
 * <p>解析顺序（{@link #resolve()}）：
 * <ol>
 *   <li>{@link #bind(ScheduledExecutorService) 显式绑定}——测试注入边界，生产代码不得依赖。</li>
 *   <li>本仓自持池（单线程 daemon {@code qcm-plan-sched}，懒创建幂等复用；生产与
 *       无 core 上下文的单测/独立运行同路径）——单线程 = fire 天然串行，与旧单车道
 *       语义等价。</li>
 * </ol>
 *
 * <p>所有权契约（{@link Resolution#ownsExecutor()}）：自持池由调用方拥有——
 * {@code QcmPlanScheduler.shutdown()}（@PreDestroy）经 {@link #shutdownPool()} 关停并清缓存；
 * 关停后再取用懒重建新池（重建有 INFO 日志，非静默兜底），动态重载/测试复用同 JVM 不滞留死池。
 *
 * @author coffee
 */
final class QcmScheduleExecutor {

    private static final Log log = LogFactory.getLogger(QcmScheduleExecutor.class);

    /** 自持池线程名（懒创建的唯一一根 daemon）。 */
    static final String POOL_THREAD_NAME = "qcm-plan-sched";

    /** 测试显式注入的调度器（bind/unbind）；null = 未注入，走默认解析。 */
    private static volatile ScheduledExecutorService bound;

    /** 自持池懒创建缓存（daemon 线程随 JVM 退出，调用方 shutdown 时负责关停）。 */
    private static final AtomicReference<ScheduledExecutorService> LOCAL =
            new AtomicReference<>();

    private QcmScheduleExecutor() {
    }

    /** 解析结果：调度器 + 所有权（ownsExecutor=true 时调用方负责关停）。 */
    static final class Resolution {

        private final ScheduledExecutorService executor;
        private final boolean ownsExecutor;

        Resolution(ScheduledExecutorService executor, boolean ownsExecutor) {
            this.executor = executor;
            this.ownsExecutor = ownsExecutor;
        }

        ScheduledExecutorService executor() {
            return executor;
        }

        boolean ownsExecutor() {
            return ownsExecutor;
        }
    }

    /**
     * 显式注入调度器（测试边界）：注入后 {@link #resolve()} 恒返回该实例（owns=false，
     * 生命周期由测试管理）。
     *
     * @param scheduler 测试自有的调度器
     */
    static void bind(ScheduledExecutorService scheduler) {
        if (scheduler == null) {
            throw new IllegalArgumentException("bind(null) 不允许——解除绑定用 unbind()");
        }
        bound = scheduler;
    }

    /** 解除显式绑定，恢复默认解析（本仓自持池）。 */
    static void unbind() {
        bound = null;
    }

    /**
     * 当前生效的调度器解析（顺序与所有权契约见类 Javadoc）。
     */
    static Resolution resolve() {
        ScheduledExecutorService explicit = bound;
        if (explicit != null) {
            return new Resolution(explicit, false);
        }
        return new Resolution(pool(), true);
    }

    /** 自持池懒创建：单线程 daemon + MDC 包装（TraceContext 传播，与旧自建池能力面一致）。 */
    private static ScheduledExecutorService pool() {
        ScheduledExecutorService existing = LOCAL.get();
        if (existing != null) {
            return existing;
        }
        return LOCAL.updateAndGet(current -> {
            if (current != null) {
                return current;
            }
            log.info("qcm 计划调度使用本仓自持 IO 域计时线程 " + POOL_THREAD_NAME);
            return MdcScheduledExecutorService.wrap(Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, POOL_THREAD_NAME);
                t.setDaemon(true);
                return t;
            }));
        });
    }

    /** 关停并遗忘自持池（幂等）：@PreDestroy 生产路径与测试还原共用；再取用时懒重建。 */
    static void shutdownPool() {
        ScheduledExecutorService existing = LOCAL.getAndSet(null);
        if (existing != null) {
            existing.shutdownNow();
        }
    }
}
