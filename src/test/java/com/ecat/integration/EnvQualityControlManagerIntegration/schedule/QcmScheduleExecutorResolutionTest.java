package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ecat.core.EcatCore;
import com.ecat.core.Task.TaskManager;

/**
 * {@link QcmScheduleExecutor} 解析路径实证：qcm 调度任务的承载方确实是解析到的
 * 调度器——bind 注入实例原样透传；默认路径落到本仓自持 IO 域计时线程（fire/rearm/
 * driftAudit 任务体走 planMapper DB IO，禁入业务池/不占引擎面），调用方拥有所有权。
 *
 * <p>验证手法：断言任务体运行线程名（任务在哪条线程执行 = 哪个调度器承载，不可伪造）。
 * 同步方式：轮询 {@code future.isDone()} 到事件发生（验证「已发生」），不用 sleep。
 */
class QcmScheduleExecutorResolutionTest {

    private static final int AWAIT_SECONDS = 10;

    private ScheduledExecutorService injected;

    /** 等待 future 完成（确定性事件等待的保险丝，正常路径毫秒级返回）。 */
    private static void awaitDone(CompletableFuture<?> future, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!future.isDone() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(future.isDone(), what + " 应在限期内完成");
    }

    @AfterEach
    void tearDown() {
        if (injected != null) {
            injected.shutdownNow();
            injected = null;
        }
        QcmScheduleExecutor.unbind();
        QcmScheduleExecutor.shutdownPool();
    }

    @Test
    void bind_returnsInjectedInstanceWithoutOwnership() {
        injected = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qcm-resolution-seam");
            t.setDaemon(true);
            return t;
        });
        QcmScheduleExecutor.bind(injected);
        QcmScheduleExecutor.Resolution r = QcmScheduleExecutor.resolve();
        assertSame(injected, r.executor(), "bind 后 resolve 恒返回注入实例");
        assertFalse(r.ownsExecutor(), "注入实例生命周期归测试，调用方不拥有");
    }

    /** 默认解析层实证：即便 core 实例存在也解析到本仓自持池（DB IO 型计时，IO 禁入业务池）。 */
    @Test
    void defaultResolution_routesToRepoOwnedPoolEvenWithCoreInstance() throws Exception {
        EcatCore previous = EcatCore.getInstance();
        EcatCore mockCore = mock(EcatCore.class);
        TaskManager taskManager = new TaskManager();
        when(mockCore.getTaskManager()).thenReturn(taskManager);
        try {
            EcatCore.setInstance(mockCore);
            QcmScheduleExecutor.Resolution first = QcmScheduleExecutor.resolve();
            assertTrue(first.ownsExecutor(), "自持池由调用方拥有（@PreDestroy 关停）");
            QcmScheduleExecutor.Resolution second = QcmScheduleExecutor.resolve();
            assertSame(first.executor(), second.executor(), "自持池懒创建幂等（同实例复用）");

            CompletableFuture<String> runner = new CompletableFuture<>();
            first.executor().schedule(() -> runner.complete(Thread.currentThread().getName()),
                    50, TimeUnit.MILLISECONDS);
            awaitDone(runner, "自持池承载的 qcm 调度任务");
            assertEquals(QcmScheduleExecutor.POOL_THREAD_NAME, runner.get(1, TimeUnit.SECONDS),
                    "任务应运行在本仓自持计时线程上（core 存在也不借引擎/业务池）");
        } finally {
            EcatCore.setInstance(previous);
            taskManager.shutdownAll();
        }
    }

    /** 关停后再取用懒重建新池（动态重载/测试复用同 JVM 不滞留死池）。 */
    @Test
    void shutdownPool_rebuildsLazilyOnNextResolve() {
        QcmScheduleExecutor.Resolution first = QcmScheduleExecutor.resolve();
        QcmScheduleExecutor.shutdownPool();
        QcmScheduleExecutor.Resolution rebuilt = QcmScheduleExecutor.resolve();
        assertNotSame(first.executor(), rebuilt.executor(), "关停后 resolve 应重建新池");
        assertTrue(rebuilt.ownsExecutor(), "重建池同样由调用方拥有");
    }
}
