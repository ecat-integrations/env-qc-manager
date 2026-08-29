package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EnvCalibrationComposerIntegration.AbstractCalibrationFlow;
import com.ecat.integration.EnvQualityControlManagerIntegration.EnvQualityControlManagerIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.impl.QcmRecordServiceImpl;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ruoyi.common.utils.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * stop 批次化 + 无执行器收敛（FR-02-19/20/21、G-BUG-2、G-PERF-6）确定性用例：
 * mock mapper/registry，断言 set-based 终止语句与 stop() 恰好一次。
 */
class QcmRecordStopBatchTest {

    private QcmRecordMapper mapper;
    private EcatCore core;
    private IntegrationRegistry registry;
    private EnvQualityControlManagerIntegration entry;
    private QcmRecordServiceImpl service;
    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getUsername).thenReturn("tester");
        mapper = mock(QcmRecordMapper.class);
        core = mock(EcatCore.class);
        registry = mock(IntegrationRegistry.class);
        entry = new EnvQualityControlManagerIntegration();
        when(core.getIntegrationRegistry()).thenReturn(registry);
        when(registry.getIntegration("integration-env-qc-manager")).thenReturn(entry);
        service = new QcmRecordServiceImpl(mapper, core);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    private QcmRecord stopTarget(Long id, String batchId) {
        QcmRecord lite = new QcmRecord();
        lite.setId(id);
        lite.setBatchId(batchId);
        lite.setExecutionStatus(ExecutionStatusEnum.RUNNING.getCode().intValue());
        return lite;
    }

    @Test
    void stopBatch_terminatesAllRowsInBatch() {
        when(mapper.selectStopTargetById(11L)).thenReturn(stopTarget(11L, "batch-1"));
        when(mapper.selectBatchRowIds("batch-1")).thenReturn(Arrays.asList(11L, 12L, 13L));
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        entry.executorMap.put(12L, flow);

        Map<String, Object> result = service.stopQcmRecord(11L);

        assertEquals(200, result.get("code"));
        // flow 只 stop 一次，批次全部行进入 STOPING（终止终态由回调写）
        verify(flow, times(1)).stop();
        verify(mapper, times(3)).markStopInProgressClearEndTime(
                anyLong(), eq(ExecutionStatusEnum.STOPPING.getCode().intValue()), any(Instant.class), any());
        verify(mapper, never()).terminateByBatchId(anyString(), anyInt(), anyString(), any(Instant.class), any());
        assertEquals(0, entry.executorMap.size());
    }

    @Test
    void stopWithoutExecutor_convergesToTerminal() {
        when(mapper.selectStopTargetById(21L)).thenReturn(stopTarget(21L, null));

        Map<String, Object> result = service.stopQcmRecord(21L);

        assertEquals(200, result.get("code"));
        // 无运行执行器：不再置 STOPING 挂死，直接终止终态（G-BUG-2）
        verify(mapper, never()).markStopInProgressClearEndTime(
                anyLong(), anyInt(), any(Instant.class), any());
        verify(mapper).terminateById(eq(21L), eq(ExecutionStatusEnum.FAILED.getCode().intValue()),
                anyString(), any(Instant.class), any());
    }

    @Test
    void stopWithoutExecutor_batchConvergesByBatchId() {
        when(mapper.selectStopTargetById(31L)).thenReturn(stopTarget(31L, "batch-9"));
        when(mapper.selectBatchRowIds("batch-9")).thenReturn(Arrays.asList(31L, 32L));

        Map<String, Object> result = service.stopQcmRecord(31L);

        assertEquals(200, result.get("code"));
        verify(mapper).terminateByBatchId(eq("batch-9"), eq(ExecutionStatusEnum.FAILED.getCode().intValue()),
                anyString(), any(Instant.class), any());
        verify(mapper, never()).terminateById(anyLong(), anyInt(), anyString(), any(Instant.class), any());
    }

    @Test
    void stopMissingRecord_returns400() {
        when(mapper.selectStopTargetById(99L)).thenReturn(null);
        Map<String, Object> result = service.stopQcmRecord(99L);
        assertEquals(400, result.get("code"));
    }

    @Test
    void perf6_existenceCheckUsesLiteColumns() {
        when(mapper.selectStopTargetById(21L)).thenReturn(stopTarget(21L, null));
        service.stopQcmRecord(21L);
        // G-PERF-6：存在性判断走轻量列查询，不再整行加载大字段（无 selectById 调用）
        verify(mapper, never()).selectById(anyLong());
    }
}
