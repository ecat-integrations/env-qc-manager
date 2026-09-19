package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rejectMessageOf 拒绝文案映射锁：三种拒绝三种文案，操作员按文案即知处置方向
 * （忙=等当前任务结束；未接线=找维护者接线；预检拒=修触发参数）。
 * 错报形态（本锁防复发）：调用方曾把三种拒绝一律硬编码「已有执行中的校准任务」，
 * 未接线 / 预检拒被误导去找并不存在的执行中任务。
 *
 * @author coffee
 */
class QcmExecutionOrchestratorRejectMessageTest {

    @Test
    void busyConflict_mapsToBusyMessage() {
        BatchResult r = BatchResult.rejectedBusyConflict("batch-1", Collections.emptyList());
        assertEquals("校准任务退出 已有执行中的校准任务",
                QcmExecutionOrchestrator.rejectMessageOf(r),
                "互斥闸拒绝才是「已有执行中的校准任务」的唯一合法场景");
    }

    @Test
    void executorTypeNotReady_mapsToNotReadyMessage() {
        BatchResult r = BatchResult.rejectedExecutorTypeNotReady("batch-1", Collections.emptyList());
        assertEquals("多仪器零点 flow 未接线",
                QcmExecutionOrchestrator.rejectMessageOf(r),
                "composer 未接线时没有任务在跑，不得报忙");
    }

    @Test
    void preTrigger_passesThroughReasonCode() {
        BatchResult r = BatchResult.rejectedPreTrigger("batch-1", Collections.emptyList(), null,
                "INVALID_PARAM");
        assertEquals("校准任务退出 INVALID_PARAM",
                QcmExecutionOrchestrator.rejectMessageOf(r),
                "预检拒无固定文案，透传结构化原因码让操作员知道去修触发参数");
    }

    @Test
    void accepted_isNotRejectable_throws() {
        BatchResult r = BatchResult.accepted("batch-1", Collections.emptyList());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> QcmExecutionOrchestrator.rejectMessageOf(r));
        assertTrue(ex.getMessage().contains("ACCEPTED"),
                "受理结果误入拒绝文案分支是调用方写错，须显式报错而非编文案：" + ex.getMessage());
    }
}
