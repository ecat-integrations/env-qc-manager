package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EnvCalibrationComposerIntegration.AbstractCalibrationFlow;
import com.ecat.integration.EnvQualityControlManagerIntegration.EnvQualityControlManagerIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.impl.QcmRecordServiceImpl;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ruoyi.common.utils.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * stop 批次化 + 无执行器收敛（FR-02-19/20/21、G-BUG-2、G-PERF-6）确定性用例：
 * mock mapper/registry，断言 set-based 终止语句与 stop() 恰好一次。
 * 停止决策核心在 {@link QcmExecutionOrchestrator}（§8 上移，REST/SDK 共用），本套件走 REST 薄壳
 * （{@code QcmRecordServiceImpl.stopQcmRecord}）串起真实编排器，mock 目标同批迁移到编排器层，
 * 用例与断言语义与上移前一致。
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
        QcmExecutionOrchestrator orchestrator = new QcmExecutionOrchestrator(
                mock(IQcmRecordService.class), mock(QcmPlanMapper.class), mapper, core,
                mock(ResultSnapshotWriter.class));
        service = new QcmRecordServiceImpl(mapper, orchestrator);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    private QcmRecord stopTarget(Long id, String batchId) {
        return stopTarget(id, batchId, ExecutionStatusEnum.RUNNING.getCode().intValue());
    }

    private QcmRecord stopTarget(Long id, String batchId, int executionStatus) {
        QcmRecord lite = new QcmRecord();
        lite.setId(id);
        lite.setBatchId(batchId);
        lite.setExecutionStatus(executionStatus);
        return lite;
    }

    @Test
    void stopBatch_terminatesAllRowsInBatch() {
        when(mapper.selectStopTargetById(11L)).thenReturn(stopTarget(11L, "batch-1"));
        when(mapper.selectBatchRowIds("batch-1")).thenReturn(Arrays.asList(11L, 12L, 13L));
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        entry.executorMap.put(12L, flow);
        // 行仍在运行中（守卫 SQL 命中前提），每行置 STOPING 各命中 1 行
        when(mapper.markStopInProgressClearEndTime(anyLong(), anyInt(), any(Instant.class), any())).thenReturn(1);

        Map<String, Object> result = service.stopQcmRecord(11L);

        assertEquals(200, result.get("code"));
        assertEquals("质控记录已中止", result.get("msg"));
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
        // 行仍处 WAITING/RUNNING（守卫 SQL 命中前提），收敛写命中 1 行才允许宣称「已中止」
        when(mapper.terminateById(eq(21L), anyInt(), anyString(), any(Instant.class), any())).thenReturn(1);

        Map<String, Object> result = service.stopQcmRecord(21L);

        assertEquals(200, result.get("code"));
        assertEquals("质控已中止（无运行执行器，直接收敛）", result.get("msg"));
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
        when(mapper.terminateByBatchId(eq("batch-9"), anyInt(), anyString(), any(Instant.class), any())).thenReturn(2);

        Map<String, Object> result = service.stopQcmRecord(31L);

        assertEquals(200, result.get("code"));
        assertEquals("质控已中止（无运行执行器，直接收敛）", result.get("msg"));
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

    @Test
    void stopAlreadyTerminalBatch_rejectedWithoutRewritingTerminalData() {
        // 缺陷#1 幂等：对已 SUCCESS 批次调 stop 不得改写 execution_status/end_time/result_evaluation。
        // 生产端由守卫 SQL `AND execution_status IN (0,1)` 保证已终态行 0 行受影响，
        // 本层断言 0 行受影响时的回执语义：不再宣称「已中止」，也不得再补任何终止写。
        when(mapper.selectStopTargetById(41L)).thenReturn(
                stopTarget(41L, "batch-41", ExecutionStatusEnum.SUCCESS.getCode().intValue()));
        when(mapper.selectBatchRowIds("batch-41")).thenReturn(Arrays.asList(41L, 42L));
        when(mapper.terminateByBatchId(eq("batch-41"), anyInt(), anyString(), any(Instant.class), any()))
                .thenReturn(0);

        Map<String, Object> result = service.stopQcmRecord(41L);

        assertEquals(400, result.get("code"));
        assertEquals("批次已结束，无需中止", result.get("msg"));
        verify(mapper, never()).markStopInProgressClearEndTime(anyLong(), anyInt(), any(Instant.class), any());
        verify(mapper, never()).terminateById(anyLong(), anyInt(), anyString(), any(Instant.class), any());
    }

    @Test
    void stopRaceWindow_terminalRowsAlreadyWritten_claimsNothingAndDoesNotConverge() {
        // 缺陷#4 竞态：回调刚落终态、executorMap 尚未清空的窗口内 stop 抢到 flow——
        // 守卫 SQL 使 markStopInProgress 0 行受影响，服务层不得宣称「已中止」，
        // 也不得再走 terminate 收敛（否则已终态行被改写、且再无回调来收敛）。
        when(mapper.selectStopTargetById(51L)).thenReturn(
                stopTarget(51L, "batch-51", ExecutionStatusEnum.FAILED.getCode().intValue()));
        when(mapper.selectBatchRowIds("batch-51")).thenReturn(Arrays.asList(51L, 52L));
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        entry.executorMap.put(51L, flow);
        when(mapper.markStopInProgressClearEndTime(anyLong(), anyInt(), any(Instant.class), any())).thenReturn(0);

        Map<String, Object> result = service.stopQcmRecord(51L);

        assertEquals(400, result.get("code"));
        assertEquals("批次已结束，无需中止", result.get("msg"));
        // flow 恰好停一次（判定终态前的那一次）；0 行受影响分支不得补发第二次 stop
        verify(flow, times(1)).stop();
        verify(mapper, never()).terminateByBatchId(anyString(), anyInt(), anyString(), any(Instant.class), any());
        verify(mapper, never()).terminateById(anyLong(), anyInt(), anyString(), any(Instant.class), any());
    }

    @Test
    void stopAlreadyStoppingBatch_reportsStillStoppingNotEnded() {
        // 重复 stop（首次已置 STOPPING、回调未回）必须如实回「正在终止」：
        // 不能谎报「已结束」，也不能对 STOPPING 行再置一次无意义的 STOPPING。
        when(mapper.selectStopTargetById(61L)).thenReturn(
                stopTarget(61L, "batch-61", ExecutionStatusEnum.STOPPING.getCode().intValue()));
        when(mapper.selectBatchRowIds("batch-61")).thenReturn(Arrays.asList(61L, 62L));
        when(mapper.terminateByBatchId(eq("batch-61"), anyInt(), anyString(), any(Instant.class), any()))
                .thenReturn(0);

        Map<String, Object> result = service.stopQcmRecord(61L);

        assertEquals(400, result.get("code"));
        assertEquals("批次正在终止，无需重复中止", result.get("msg"));
        // 0 行分支不得重置 STOPPING，也不得再补任何收敛写（首次 stop 已把 flow 移出 map，此处无 flow 可触碰）
        verify(mapper, never()).markStopInProgressClearEndTime(anyLong(), anyInt(), any(Instant.class), any());
        verify(mapper, never()).terminateById(anyLong(), anyInt(), anyString(), any(Instant.class), any());
        assertEquals(0, entry.executorMap.size());
    }

    @Test
    void stopGuardSql_presentOnAllStopUpdateStatements() throws Exception {
        // 缺陷#1/#4 的根治点在 SQL 本身：mock mapper 测不到 SQL 形状（同 MapperXmlInjectionGuardTest 的静态兜底思路），
        // 故断言 stop 链三条 UPDATE 全部带状态守卫——已终态行（SUCCESS/FAILED）与已在终止中（STOPPING）不可再改写。
        Path xml = Paths.get("src/main/resources/mapper/quality_control/QcmRecordMapper.xml");
        String content = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
        for (String statementId : Arrays.asList("markStopInProgressClearEndTime", "terminateById", "terminateByBatchId")) {
            Matcher matcher = Pattern
                    .compile("<update id=\"" + statementId + "\">(.*?)</update>", Pattern.DOTALL)
                    .matcher(content);
            assertTrue(matcher.find(), "缺少 update 语句 " + statementId);
            String normalized = matcher.group(1).toLowerCase().replaceAll("\\s+", " ");
            assertTrue(normalized.contains("and execution_status in (0, 1)"),
                    statementId + " 必须带 AND execution_status IN (0, 1) 守卫（终态行不可改写）");
        }
    }
}
