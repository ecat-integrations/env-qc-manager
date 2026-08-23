package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 精确唤醒调度器确定性用例：不起真线程、零 sleep——注入可控 {@link SettableClock}、
 * 捕获型同线程 executor（schedule 只记录 runnable+delay 供手动 run）、Mockito mock mapper/provider。
 *
 * <p>基准 now = 2026-08-21T10:00:00+08:00（Asia/Shanghai，DAILY 10:00 当点触发场景）。</p>
 */
class QcmPlanSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private QcmPlanMapper planMapper;
    private PlanFireAction fireAction;
    private ObjectProvider<PlanFireAction> provider;
    private SettableClock clock;
    private CapturingExecutor executor;
    private QcmPlanScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        planMapper = mock(QcmPlanMapper.class);
        fireAction = mock(PlanFireAction.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fireAction);
        clock = new SettableClock(wall(2026, 8, 21, 10, 0), ZONE);
        executor = new CapturingExecutor();
        scheduler = new QcmPlanScheduler(planMapper, provider, clock, executor);
    }

    // ===== 用例 =====

    @Test
    void start_loadsActivePlansAndArmsEarliest() {
        // 两 ACTIVE 计划，rearm 只取 DB 最早行挂一次唤醒（delay 断言到毫秒）
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":30}", wall(2026, 8, 21, 10, 30)));

        scheduler.start();

        assertEquals(1, executor.scheduled.size());
        assertEquals(Duration.ofMinutes(30).toMillis(), executor.scheduled.get(0).delayMs);
    }

    @Test
    void fire_executesActionThenUpdatesNext() {
        // next=now 到点触发：调 action → next 推进到次日 10:00 → last=now
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", clock.instant()));
        when(planMapper.selectById(1L)).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", clock.instant()));
        scheduler.start();
        executor.scheduled.get(0).runnable.run();

        verify(fireAction).fire(1L);
        ArgumentCaptor<Instant> nextCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(planMapper).updateNextFireTime(eq(1L), nextCaptor.capture(), eq(clock.instant()));
        assertEquals(wall(2026, 8, 22, 10, 0), nextCaptor.getValue());
    }

    @Test
    void fire_rearmsAfterExecution() {
        // fire 后（onFire finally）重新挂：DB 行已推进到次日 → 新 delay 24h
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        when(planMapper.selectById(1L)).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", clock.instant()));
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", clock.instant()),
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", wall(2026, 8, 22, 10, 0)));
        scheduler.start();
        executor.scheduled.get(0).runnable.run();

        assertEquals(2, executor.scheduled.size());
        assertEquals(Duration.ofHours(24).toMillis(), executor.scheduled.get(1).delayMs);
    }

    @Test
    void fire_onceType_marksFinishedNoRearm() {
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        when(planMapper.selectById(1L)).thenReturn(
                activePlan(1L, "p1", "ONCE", "{\"onceAt\":\"2026-08-21T02:00:00Z\"}", clock.instant()));
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "ONCE", "{\"onceAt\":\"2026-08-21T02:00:00Z\"}", clock.instant()),
                null); // FINISHED 后无候选
        scheduler.start();
        executor.scheduled.get(0).runnable.run();

        verify(fireAction).fire(1L);
        verify(planMapper).updateNextFireTime(eq(1L), isNull(), eq(clock.instant()));
        verify(planMapper).updateStatus(eq(1L), eq("FINISHED"), eq(QcmPlanScheduler.SCHEDULER_ACTOR));
        assertEquals(1, executor.scheduled.size(), "ONCE 触发后不得再挂新唤醒");
    }

    @Test
    void pastDue_skipsExecutionWithoutBackfill() {
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        when(planMapper.selectById(1L)).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", clock.instant()));
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", clock.instant()));
        scheduler.start();
        // 挂起后时钟前推 120s 再 run → now - next > 60s 视为停机错过：不调 action、仅重算
        clock.set(wall(2026, 8, 21, 10, 2));
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", wall(2026, 8, 22, 10, 0)));
        executor.scheduled.get(0).runnable.run();

        verify(fireAction, never()).fire(anyLong());
        ArgumentCaptor<Instant> nextCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(planMapper).updateNextFireTime(eq(1L), nextCaptor.capture(), eq(wall(2026, 8, 21, 10, 2)));
        assertEquals(wall(2026, 8, 22, 10, 0), nextCaptor.getValue());
    }

    @Test
    void pause_planRemovedFromScheduling() {
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":30}", wall(2026, 8, 21, 10, 30)));
        scheduler.start();
        assertEquals(1, executor.scheduled.size());

        // 暂停后该计划 next_fire_time 置空 → 最早行无候选 → 不再被 arm
        when(planMapper.selectEarliestActive()).thenReturn(null);
        scheduler.notifyPlanChanged(1L);

        assertTrue(executor.scheduled.get(0).future.isCancelled(), "已挂唤醒须被取消");
        assertEquals(1, executor.scheduled.size(), "无候选时不得挂新任务");
    }

    @Test
    void driftAudit_rearmsOnMismatch() {
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":11,\"minute\":0}", wall(2026, 8, 21, 11, 0)));
        scheduler.start();
        // DB 出现更早的计划 2（漂移/漏挂）→ 巡检 rearm 挂 10:30 的 30min 唤醒
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(2L, "p2", "DAILY", "{\"hour\":10,\"minute\":30}", wall(2026, 8, 21, 10, 30)));

        scheduler.runDriftAudit();

        assertEquals(2, executor.scheduled.size(), "巡检发现不一致须 rearm");
        assertEquals(Duration.ofMinutes(30).toMillis(), executor.scheduled.get(1).delayMs);
        assertTrue(executor.scheduled.get(0).future.isCancelled());
    }

    @Test
    void start_isIdempotent() {
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":30}", wall(2026, 8, 21, 10, 30)));
        scheduler.start();
        scheduler.start();
        assertEquals(1, executor.scheduled.size(), "二次 start 不得重复挂");
    }

    @Test
    void shutdown_cancelsPendingTask() {
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        when(planMapper.selectEarliestActive()).thenReturn(
                activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":30}", wall(2026, 8, 21, 10, 30)));
        scheduler.start();

        scheduler.shutdown();

        assertTrue(executor.scheduled.get(0).future.isCancelled(), "shutdown 须取消已挂唤醒");
        assertTrue(executor.fixedRateFuture.isCancelled(), "shutdown 须取消漂移巡检");
        // shutdown 后不再消费唤醒（onFire stopped 直退）
        executor.scheduled.get(0).runnable.run();
        verify(fireAction, never()).fire(anyLong());
    }

    @Test
    void orphanWakeup_duplicateFingerprint_skipsSecondAction() {
        // 场景 A 残余：孤儿任务与首跑同 planId+due 二次进 fireInternal → action 只一次、advancePlan 两次
        QcmPlan due = activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", clock.instant());
        when(planMapper.selectById(1L)).thenReturn(due);

        scheduler.fireInternal(1L);
        scheduler.fireInternal(1L);

        verify(fireAction, times(1)).fire(1L);
        verify(planMapper, times(2)).updateNextFireTime(eq(1L), any(Instant.class), any(Instant.class));
    }

    @Test
    void advanceWriteFails_noHotLoop() {
        // 场景 B：updateNextFireTime 首次抛异常 → rearm 读到旧 next（delay 负立即再挂）→ 二次 run 同指纹被闩拦截
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        QcmPlan plan = activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", clock.instant());
        when(planMapper.selectById(1L)).thenReturn(plan);
        when(planMapper.selectEarliestActive()).thenReturn(plan); // 写失败 DB 未推进，rearm 一直读到旧 due
        when(planMapper.updateNextFireTime(anyLong(), any(), any()))
                .thenThrow(new RuntimeException("db write down"))
                .thenReturn(0);
        scheduler.start();

        executor.scheduled.get(0).runnable.run(); // 首跑：action 执行、写失败（onFire catch error）
        executor.scheduled.get(1).runnable.run(); // 热循环重跑：同指纹 → 闩拦截，action 不再执行

        verify(fireAction, times(1)).fire(1L);
        // 第二轮 advancePlan 重试写（收敛路径），不再是首轮那次失败
        verify(planMapper, times(2)).updateNextFireTime(anyLong(), any(), any());
    }

    @Test
    void onFire_clearsOnlyOwnFuture() {
        // 场景 A 第一道闸：notify 抢先 rearm 换上新句柄后孤儿唤醒跑 onFire → 不得清掉新句柄
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Collections.emptyList());
        QcmPlan plan = activePlan(1L, "p1", "DAILY", "{\"hour\":10,\"minute\":0}", clock.instant());
        when(planMapper.selectById(1L)).thenReturn(plan);
        when(planMapper.selectEarliestActive()).thenReturn(plan);
        scheduler.start(); // task1 = 本次孤儿唤醒
        scheduler.notifyPlanChanged(1L); // 抢先取消 task1 并挂 task2（armedFuture 换成 task2 句柄）

        // 孤儿 task1 执行期间（action.fire 回调内）新句柄须仍存活；收口 rearm 才允许取消它换 task3
        doAnswer(inv -> {
            assertTrue(!executor.scheduled.get(1).future.isCancelled(),
                    "onFire 执行期间不得取消 notify 挂上的新任务");
            return null;
        }).when(fireAction).fire(1L);
        executor.scheduled.get(0).runnable.run();

        assertTrue(executor.scheduled.get(1).future.isCancelled(),
                "收口 rearm 应取消旧句柄换新任务（正常重挂），而非孤儿并存");
        assertEquals(3, executor.scheduled.size(), "孤儿跑完收口后应有 task2 + task3 两次挂载");
    }

    // ===== helpers =====

    /** 上海墙钟 → Instant。 */
    private static Instant wall(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, ZONE).toInstant();
    }

    private static QcmPlan activePlan(Long id, String name, String type, String config, Instant next) {
        QcmPlan plan = new QcmPlan();
        plan.setId(id);
        plan.setPlanName(name);
        plan.setScheduleType(type);
        plan.setScheduleConfig(config);
        plan.setStatus("ACTIVE");
        plan.setNextFireTime(next);
        return plan;
    }

    /** 可手动设置当前时刻的 Clock（确定性时间推进，替代真等待）。 */
    private static final class SettableClock extends Clock {
        private volatile Instant now;
        private final ZoneId zone;

        SettableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new SettableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 捕获型同线程 executor：schedule 只记录 runnable+delay，由测试手动 run；固定率任务返回可断言 future。 */
    private static final class CapturingExecutor implements ScheduledExecutorService {

        final List<CapturedTask> scheduled = new ArrayList<>();
        final TrackableFuture fixedRateFuture = new TrackableFuture();

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            TrackableFuture future = new TrackableFuture();
            scheduled.add(new CapturedTask(command, unit.toMillis(delay), future));
            return future;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return fixedRateFuture;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("scheduler does not use schedule(Callable)");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("scheduler does not use scheduleWithFixedDelay");
        }

        @Override
        public void shutdown() {
            // 测试 executor 由测试自管，不实现
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                                                  long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
    }

    /** 捕获的一次 schedule：runnable / delay 毫秒 / 返回的 future。 */
    private static final class CapturedTask {
        final Runnable runnable;
        final long delayMs;
        final TrackableFuture future;

        CapturedTask(Runnable runnable, long delayMs, TrackableFuture future) {
            this.runnable = runnable;
            this.delayMs = delayMs;
            this.future = future;
        }
    }

    /** 可断言取消状态的 ScheduledFuture 桩（调度器只调 cancel）。 */
    private static final class TrackableFuture implements ScheduledFuture<Object> {
        volatile boolean cancelled = false;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
