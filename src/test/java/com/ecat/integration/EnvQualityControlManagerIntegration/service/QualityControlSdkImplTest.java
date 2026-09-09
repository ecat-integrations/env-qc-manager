package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.api.QualityControlSdk;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.ResultFilter;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkBatchState;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkExecutionResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkPlanSetting;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkRecordDetail;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerReply;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordKeyParam;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPhase;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPoint;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordKeyParamMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPhaseMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPointMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对外 SDK 实现确定性用例：mock 编排器/记录服务/mapper，校验器用真实实例
 * （证明 FR-03-17 复用：拒绝口径与计划保存同源）。
 */
class QualityControlSdkImplTest {

    private QcmExecutionOrchestrator orchestrator;
    private IQcmRecordService recordService;
    private QcmRecordMapper recordMapper;
    private QcmRecordPhaseMapper phaseMapper;
    private QcmRecordKeyParamMapper keyParamMapper;
    private QcmRecordPointMapper pointMapper;
    private IQcmPlanService planService;
    private QualityControlSdkImpl sdk;

    @BeforeEach
    void setUp() {
        orchestrator = mock(QcmExecutionOrchestrator.class);
        recordService = mock(IQcmRecordService.class);
        recordMapper = mock(QcmRecordMapper.class);
        phaseMapper = mock(QcmRecordPhaseMapper.class);
        keyParamMapper = mock(QcmRecordKeyParamMapper.class);
        pointMapper = mock(QcmRecordPointMapper.class);
        planService = mock(IQcmPlanService.class);
        sdk = new QualityControlSdkImpl(orchestrator, recordService, recordMapper,
                new PlanParamValidator(), phaseMapper, keyParamMapper, pointMapper, planService);
    }

    private static SdkTriggerRequest validSpanRequest() {
        return SdkTriggerRequest.builder()
                .qcType("span_check")
                .instruments(Collections.singletonList("SO2"))
                .concentrationPpb(BigDecimal.valueOf(400))
                .sourceName("lims-system")
                .allowQueue(false)
                .build();
    }

    @Test
    void triggerAcceptedPassesThroughBatchIdAndRecordIds() {
        when(orchestrator.triggerExecution(any(), eq(TriggerSource.REMOTE), eq("lims-system")))
                .thenReturn(BatchResult.accepted("batch-1", Arrays.asList(11L, 12L)));

        SdkTriggerReply reply = sdk.trigger(validSpanRequest());

        assertTrue(reply.isAccepted());
        assertEquals("batch-1", reply.getBatchId());
        assertEquals(Arrays.asList(11L, 12L), reply.getRecordIds());
        assertNull(reply.getReason());
    }

    @Test
    void triggerAssemblesRemoteRequestWithSelfDescribingSnapshot() {
        when(orchestrator.triggerExecution(any(), eq(TriggerSource.REMOTE), eq("lims-system")))
                .thenReturn(BatchResult.accepted("batch-1", Collections.singletonList(11L)));

        sdk.trigger(validSpanRequest());

        ArgumentCaptor<QcExecutionRequest> captor = ArgumentCaptor.forClass(QcExecutionRequest.class);
        verify(orchestrator).triggerExecution(captor.capture(), eq(TriggerSource.REMOTE), eq("lims-system"));
        QcExecutionRequest req = captor.getValue();
        assertNull(req.getPlanId());
        assertEquals("span_check", req.getQcType());
        assertEquals(Collections.singletonList("SO2"), req.getInstruments());
        assertEquals(0, BigDecimal.valueOf(400).compareTo(req.getConcentrationPpb()));
        assertNotNull(req.getPlanSnapshotJson());
        assertTrue(req.getPlanSnapshotJson().contains("REMOTE_SDK"));
        assertTrue(req.getPlanSnapshotJson().contains("lims-system"));
    }

    @Test
    void triggerWithAllowQueueRejectedWithoutTouchingOrchestrator() {
        SdkTriggerRequest request = SdkTriggerRequest.builder()
                .qcType("span_check")
                .instruments(Collections.singletonList("SO2"))
                .concentrationPpb(BigDecimal.valueOf(400))
                .sourceName("lims-system")
                .allowQueue(true)
                .build();

        SdkTriggerReply reply = sdk.trigger(request);

        assertFalse(reply.isAccepted());
        assertEquals(QualityControlSdk.REASON_QUEUE_NOT_SUPPORTED, reply.getReason());
        assertNull(reply.getBatchId());
        verify(orchestrator, never()).triggerExecution(any(), any(), any());
    }

    @Test
    void triggerWithBlankSourceNameRejectedInvalidParam() {
        SdkTriggerReply reply = sdk.trigger(SdkTriggerRequest.builder()
                .qcType("span_check")
                .instruments(Collections.singletonList("SO2"))
                .concentrationPpb(BigDecimal.valueOf(400))
                .sourceName("  ")
                .allowQueue(false)
                .build());

        assertFalse(reply.isAccepted());
        assertEquals(QualityControlSdk.REASON_INVALID_PARAM, reply.getReason());
        assertTrue(reply.getMessage().contains("sourceName"));
        verify(orchestrator, never()).triggerExecution(any(), any(), any());
    }

    @Test
    void triggerWithMissingConcentrationRejectedBySharedValidatorRules() {
        // span_check 必填标气浓度（与计划保存同一校验器，FR-03-17）
        SdkTriggerReply reply = sdk.trigger(SdkTriggerRequest.builder()
                .qcType("span_check")
                .instruments(Collections.singletonList("SO2"))
                .sourceName("lims-system")
                .allowQueue(false)
                .build());

        assertFalse(reply.isAccepted());
        assertEquals(QualityControlSdk.REASON_INVALID_PARAM, reply.getReason());
        assertTrue(reply.getMessage().contains("标气浓度"));
        verify(orchestrator, never()).triggerExecution(any(), any(), any());
    }

    @Test
    void triggerBusyConflictPassesThroughRecordIds() {
        when(orchestrator.triggerExecution(any(), eq(TriggerSource.REMOTE), eq("lims-system")))
                .thenReturn(BatchResult.rejectedBusyConflict("batch-2", Arrays.asList(21L, 22L)));

        SdkTriggerReply reply = sdk.trigger(validSpanRequest());

        assertFalse(reply.isAccepted());
        assertEquals(QualityControlSdk.REASON_BUSY_CONFLICT, reply.getReason());
        assertEquals("batch-2", reply.getBatchId());
        assertEquals(Arrays.asList(21L, 22L), reply.getRecordIds());
    }

    @Test
    void triggerExecutorNotReadyMappedFromIllegalState() {
        when(orchestrator.triggerExecution(any(), eq(TriggerSource.REMOTE), eq("lims-system")))
                .thenThrow(new IllegalStateException("无可用结果格式化器（集成入口未注册），质控类型: span_check"));

        SdkTriggerReply reply = sdk.trigger(validSpanRequest());

        assertFalse(reply.isAccepted());
        assertEquals(QualityControlSdk.REASON_EXECUTOR_TYPE_NOT_READY, reply.getReason());
        assertTrue(reply.getMessage().contains("无可用结果格式化器"));
    }

    @Test
    void queryExecutionMixedStatusIsNotTerminal() {
        QcmRecord running = record(31L, 1);
        QcmRecord success = record(32L, 2);
        when(recordMapper.selectByBatchId("batch-3")).thenReturn(Arrays.asList(running, success));

        SdkBatchState state = sdk.queryExecution("batch-3");

        assertFalse(state.isTerminal());
        assertEquals(2, state.getRecords().size());
        assertEquals("SO2", state.getRecords().get(0).getInstrument());
        assertEquals("执行中", state.getRecords().get(0).getStatusName());
        assertEquals("成功", state.getRecords().get(1).getStatusName());
    }

    @Test
    void queryExecutionAllTerminalIsTerminal() {
        when(recordMapper.selectByBatchId("batch-4")).thenReturn(Arrays.asList(
                record(41L, 2), record(42L, 3)));

        assertTrue(sdk.queryExecution("batch-4").isTerminal());
    }

    @Test
    void queryExecutionUnknownBatchThrowsIllegalArgument() {
        when(recordMapper.selectByBatchId("nope")).thenReturn(Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () -> sdk.queryExecution("nope"));
        assertThrows(IllegalArgumentException.class, () -> sdk.queryExecution(" "));
    }

    @Test
    void getRecordDetailReturnsFullReportLevelFields() {
        QcmRecord record = record(51L, 2);
        record.setBatchId("batch-5");
        record.setPlanId(null);
        record.setQualityControlType("span_check");
        record.setTaskType(TriggerSource.REMOTE.getCode());
        record.setTriggerUser("lims-system");
        record.setFailureReason(null);
        record.setMonitoringData(BigDecimal.valueOf(401.2));
        record.setStandardValue(BigDecimal.valueOf(400));
        record.setCalculatedValue(BigDecimal.valueOf(0.3));
        record.setResultEvaluation("校准任务完成，且已通过");
        record.setRecordSnapshot("{\"sourceName\":\"lims-system\",\"qcType\":\"span_check\"}");
        // 阶段时间线改读 qcm_record_phase 子表（§4.0 强类型读面）；execution_log JSON 残留不再被解析
        record.setExecutionLog("{\"qcPhaseTimelines\":[{\"phaseCode\":DEAD_JSON\"}");
        QcmRecordPhase phaseRow = new QcmRecordPhase();
        phaseRow.setRecordId(51L);
        phaseRow.setSeq(0);
        phaseRow.setPhaseCode("calibration");
        phaseRow.setPhaseName("校准");
        phaseRow.setEstimatedSeconds(300);
        phaseRow.setStartTime(Instant.ofEpochMilli(1700000000000L));
        phaseRow.setEndTime(Instant.ofEpochMilli(1700000300000L));
        when(phaseMapper.selectByRecordId(51L)).thenReturn(Collections.singletonList(phaseRow));
        when(recordService.selectQcmRecordById(51L)).thenReturn(record);

        SdkRecordDetail detail = sdk.getRecordDetail(51L);

        assertEquals(51L, detail.getRecordId());
        assertEquals("batch-5", detail.getBatchId());
        assertNull(detail.getPlanId());
        assertEquals("span_check", detail.getQcType());
        assertEquals("SO2", detail.getInstrument());
        assertEquals("REMOTE", detail.getTriggerSource());
        assertEquals("lims-system", detail.getTriggerUser());
        assertEquals(2, detail.getExecutionStatus());
        assertEquals("成功", detail.getExecutionStatusName());
        assertEquals(0, BigDecimal.valueOf(401.2).compareTo(detail.getMonitoringData()));
        assertEquals(0, BigDecimal.valueOf(400).compareTo(detail.getStandardValue()));
        assertEquals(0, BigDecimal.valueOf(0.3).compareTo(detail.getCalculatedValue()));
        assertEquals("校准任务完成，且已通过", detail.getResultEvaluation());
        assertEquals(1, detail.getPhaseTimelines().size());
        SdkRecordDetail.PhaseTimeline phase = detail.getPhaseTimelines().get(0);
        assertEquals("calibration", phase.getPhaseId());
        assertEquals("校准", phase.getPhaseName());
        assertEquals(Long.valueOf(300), phase.getEstimatedSeconds());
        assertEquals(Instant.ofEpochMilli(1700000000000L), phase.getStart());
        assertEquals(Instant.ofEpochMilli(1700000300000L), phase.getEnd());
        assertEquals("lims-system", detail.getRecordSnapshot().get("sourceName"));
        assertEquals("span_check", detail.getRecordSnapshot().get("qcType"));
    }

    @Test
    void getRecordDetailMissingRecordThrowsIllegalArgument() {
        when(recordService.selectQcmRecordById(99L)).thenReturn(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sdk.getRecordDetail(99L));
        assertTrue(ex.getMessage().contains("99"));
    }

    // ===== queryResults / getExecutionResult（§4.0 数据面） =====

    @Test
    void queryResults_nullOrEmptyFilterThrows() {
        assertThrows(IllegalArgumentException.class, () -> sdk.queryResults(null));
        assertThrows(IllegalArgumentException.class,
                () -> sdk.queryResults(ResultFilter.builder().build()));
        verify(recordMapper, never()).selectByFilter(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void queryResults_mapsAllFilterDimensionsToMapperArgs() {
        Instant begin = Instant.ofEpochMilli(1L);
        Instant end = Instant.ofEpochMilli(2L);
        when(recordMapper.selectByFilter(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(Collections.emptyList());

        sdk.queryResults(ResultFilter.builder()
                .begin(begin).end(end).qcType("span_check").instrument("SO2")
                .triggerSource("REMOTE").batchId("batch-9").triggerRequestId("req-1").build());

        org.mockito.ArgumentCaptor<String> sourceCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(recordMapper).selectByFilter(eq(begin), eq(end), eq("span_check"), eq("SO2"),
                sourceCaptor.capture(), eq("batch-9"), eq("req-1"),
                org.mockito.ArgumentMatchers.eq(501));
        assertEquals(TriggerSource.REMOTE.getCode(), sourceCaptor.getValue());
    }

    @Test
    void queryResults_unknownTriggerSourceThrows() {
        assertThrows(IllegalArgumentException.class, () -> sdk.queryResults(
                ResultFilter.builder().triggerSource("NOT_A_SOURCE").build()));
    }

    @Test
    void queryResults_overLimitThrowsWithNarrowHint() {
        List<QcmRecord> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(record(i, 2));
        }
        when(recordMapper.selectByFilter(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(rows);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sdk.queryResults(ResultFilter.builder().qcType("span_check").build()));
        assertTrue(ex.getMessage().contains("500"));
        assertTrue(ex.getMessage().contains("收窄"));
    }

    @Test
    void getExecutionResult_missingRecordThrows() {
        when(recordService.selectQcmRecordById(99L)).thenReturn(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sdk.getExecutionResult(99L));
        assertTrue(ex.getMessage().contains("99"));
    }

    /** 五层组装逐字段：事件/过程/判定（标量+point 序列）/工况溯源/结论。 */
    @Test
    void getExecutionResult_assemblesFiveLayersFromTypedColumns() {
        QcmRecord record = record(61L, 2);
        record.setBatchId("batch-6");
        record.setTriggerRequestId("req-6");
        record.setPlanId(3L);
        record.setQualityControlType("multi_check");
        record.setTaskType(TriggerSource.SCHEDULED.getCode());
        record.setTriggerUser("system");
        record.setEndTime(Instant.ofEpochMilli(1_700_000_900_000L));
        record.setFailureReason(null);
        record.setStandardValue(new BigDecimal("100"));
        record.setMonitoringData(new BigDecimal("101"));
        record.setCalculatedValue(new BigDecimal("1"));
        record.setCheckPassLimit(new BigDecimal("2"));
        record.setCheckCalibLimit(new BigDecimal("10"));
        record.setIsPass(Boolean.TRUE);
        record.setSlope(new BigDecimal("0.998"));
        record.setIntercept(new BigDecimal("0.12"));
        record.setCorrelation(new BigDecimal("0.9999"));
        record.setSamplingStartTime(Instant.ofEpochMilli(1_700_000_000_000L));
        record.setSamplingEndTime(Instant.ofEpochMilli(1_700_000_060_000L));
        record.setInstrumentName("赛默飞世尔");
        record.setInstrumentNo("CMNO001");
        record.setGasSource("国家标准物质研究所");
        record.setGasNo("GBW-E-050123");
        record.setGasConcentration(new BigDecimal("400"));
        record.setFullScale(new BigDecimal("500"));
        record.setFlowType("air.monitor.calibration.multi_check");
        record.setFlowExecutionRef("61@1700000000000");
        record.setResultEvaluation("校准任务完成，且已通过");
        when(recordService.selectQcmRecordById(61L)).thenReturn(record);

        QcmRecordPhase phase = new QcmRecordPhase();
        phase.setRecordId(61L);
        phase.setSeq(0);
        phase.setPhaseCode("check");
        phase.setPhaseName("读数");
        phase.setEstimatedSeconds(60);
        phase.setStartTime(Instant.ofEpochMilli(1_700_000_000_000L));
        phase.setEndTime(Instant.ofEpochMilli(1_700_000_006_000L));
        when(phaseMapper.selectByRecordId(61L)).thenReturn(Collections.singletonList(phase));

        QcmRecordKeyParam keyParam = new QcmRecordKeyParam();
        keyParam.setRecordId(61L);
        keyParam.setSeq(0);
        keyParam.setName("主浓度");
        keyParam.setValue("101 ppb");
        keyParam.setUnit(null);
        keyParam.setRefRange("80~120");
        when(keyParamMapper.selectByRecordId(61L)).thenReturn(Collections.singletonList(keyParam));

        QcmRecordPoint p0 = new QcmRecordPoint();
        p0.setRecordId(61L);
        p0.setSeq(0);
        p0.setStdValue(new BigDecimal("0"));
        p0.setDeviceValue(new BigDecimal("0.5"));
        QcmRecordPoint p1 = new QcmRecordPoint();
        p1.setRecordId(61L);
        p1.setSeq(1);
        p1.setStdValue(new BigDecimal("100"));
        p1.setDeviceValue(new BigDecimal("101"));
        when(pointMapper.selectByRecordId(61L)).thenReturn(Arrays.asList(p0, p1));

        SdkExecutionResult result = sdk.getExecutionResult(61L);

        // 事件层
        assertEquals(61L, result.getRecordId());
        assertEquals("batch-6", result.getBatchId());
        assertEquals("req-6", result.getTriggerRequestId());
        assertEquals(Long.valueOf(3L), result.getPlanId());
        assertEquals("multi_check", result.getQcType());
        assertEquals("SO2", result.getInstrument());
        assertEquals("SCHEDULED", result.getTriggerSource());
        assertEquals("system", result.getTriggerUser());
        assertEquals(Instant.ofEpochMilli(1_700_000_900_000L), result.getEndTime());
        assertEquals(2, result.getExecutionStatus());
        assertEquals("成功", result.getExecutionStatusName());
        // 过程层（子表）
        assertEquals(1, result.getPhaseTimelines().size());
        assertEquals("check", result.getPhaseTimelines().get(0).getPhaseId());
        assertEquals(Long.valueOf(60), result.getPhaseTimelines().get(0).getEstimatedSeconds());
        assertEquals(Instant.ofEpochMilli(1_700_000_006_000L), result.getPhaseTimelines().get(0).getEnd());
        // 判定层：标量 + point 序列
        SdkExecutionResult.Judgement j = result.getJudgement();
        assertEquals(new BigDecimal("1"), j.getResultValue());
        assertEquals(new BigDecimal("100"), j.getStdValue());
        assertEquals(new BigDecimal("101"), j.getDeviceValue());
        assertEquals(new BigDecimal("2"), j.getCheckPassLimit());
        assertEquals(new BigDecimal("10"), j.getCheckCalibLimit());
        assertEquals(Boolean.TRUE, j.getPass());
        assertEquals(Arrays.asList(new BigDecimal("0"), new BigDecimal("100")), j.getStdValues());
        assertEquals(Arrays.asList(new BigDecimal("0.5"), new BigDecimal("101")), j.getDeviceValues());
        assertEquals(new BigDecimal("0.998"), j.getSlope());
        assertEquals(new BigDecimal("0.12"), j.getIntercept());
        assertEquals(new BigDecimal("0.9999"), j.getCorrelation());
        // 工况+溯源层
        assertEquals(1, result.getKeyParameters().size());
        assertEquals("主浓度", result.getKeyParameters().get(0).getName());
        assertNull(result.getKeyParameters().get(0).getUnit());
        assertEquals("80~120", result.getKeyParameters().get(0).getRefRange());
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), result.getSamplingStartTime());
        assertEquals(Instant.ofEpochMilli(1_700_000_060_000L), result.getSamplingEndTime());
        assertEquals("赛默飞世尔", result.getInstrumentName());
        assertEquals("CMNO001", result.getInstrumentNo());
        assertEquals("国家标准物质研究所", result.getGasSource());
        assertEquals("GBW-E-050123", result.getGasNo());
        assertEquals(new BigDecimal("400"), result.getGasConcentration());
        assertEquals(new BigDecimal("500"), result.getFullScale());
        assertEquals("air.monitor.calibration.multi_check", result.getFlowType());
        assertEquals("61@1700000000000", result.getFlowExecutionRef());
        // 结论层
        assertEquals("校准任务完成，且已通过", result.getResultEvaluation());
    }

    /** 判定层快照缺失（未执行完成的行）：标量/序列如实 null/空列表，不伪造。 */
    @Test
    void getExecutionResult_missingSnapshotFieldsStayNull() {
        QcmRecord record = record(71L, 1);
        when(recordService.selectQcmRecordById(71L)).thenReturn(record);

        SdkExecutionResult result = sdk.getExecutionResult(71L);

        SdkExecutionResult.Judgement j = result.getJudgement();
        assertNotNull(j);
        assertNull(j.getResultValue());
        assertNull(j.getPass());
        assertTrue(j.getStdValues().isEmpty());
        assertTrue(j.getDeviceValues().isEmpty());
        assertTrue(result.getPhaseTimelines().isEmpty());
        assertTrue(result.getKeyParameters().isEmpty());
        assertNull(result.getGasSource());
        assertNull(result.getFlowType());
    }

    private static QcmRecord record(long id, int status) {
        QcmRecord record = new QcmRecord();
        record.setId(id);
        record.setBatchId("batch-x");
        record.setParameter("SO2");
        record.setExecutionStatus(status);
        record.setStartTime(Instant.now());
        return record;
    }

    // ===== queryPlans（质控计划当前设置） =====

    @Test
    void queryPlans_mapsScheduleAndInstrumentsForActivePlan() {
        QcmPlan plan = plan(7L, "ACTIVE", "WEEKLY", "{\"hour\":9,\"minute\":30,\"weekdays\":[1,3,5]}");
        plan.setQcType("span_check");
        plan.setInstruments("[\"SO2\",\"NO2\"]");
        when(planService.selectList(any())).thenReturn(Collections.singletonList(plan));

        List<SdkPlanSetting> result = sdk.queryPlans(null);

        assertEquals(1, result.size());
        SdkPlanSetting s = result.get(0);
        assertEquals(7L, s.getPlanId());
        assertEquals("span_check", s.getQcType());
        assertEquals(Arrays.asList("SO2", "NO2"), s.getInstruments());
        assertEquals("WEEKLY", s.getScheduleType());
        assertEquals(9, s.getHour());
        assertEquals(30, s.getMinute());
        assertTrue(s.getWeekdays().containsAll(Arrays.asList(1, 3, 5)));
        assertEquals("ACTIVE", s.getStatus());
        assertTrue(s.isEnabled());
    }

    @Test
    void queryPlans_withoutStatusFilterExcludesFinishedAndMapsEnabled() {
        QcmPlan active = plan(1L, "ACTIVE", "DAILY", "{\"hour\":1,\"minute\":0}");
        QcmPlan paused = plan(2L, "PAUSED", "DAILY", "{\"hour\":2,\"minute\":0}");
        QcmPlan finished = plan(3L, "FINISHED", "ONCE", "{\"onceAt\":\"2026-01-01T00:00:00Z\"}");
        when(planService.selectList(any())).thenReturn(Arrays.asList(active, paused, finished));

        List<SdkPlanSetting> result = sdk.queryPlans(null);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getPlanId());
        assertTrue(result.get(0).isEnabled());
        assertEquals(2L, result.get(1).getPlanId());
        assertFalse(result.get(1).isEnabled());
    }

    @Test
    void queryPlans_withStatusFilterPassesThroughAndSkipsBadSchedule() {
        QcmPlan good = plan(1L, "ACTIVE", "DAILY", "{\"hour\":8,\"minute\":0}");
        // WEEKLY 缺 weekdays → ScheduleSpec 构造抛，toPlanSetting 跳过不整批失败
        QcmPlan bad = plan(2L, "ACTIVE", "WEEKLY", "{\"hour\":8,\"minute\":0}");
        when(planService.selectList(any())).thenReturn(Arrays.asList(good, bad));

        List<SdkPlanSetting> result = sdk.queryPlans("ACTIVE");

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getPlanId());
        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planService).selectList(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getStatus());
    }

    @Test
    void queryPlans_nullServiceResultReturnsEmpty() {
        when(planService.selectList(any())).thenReturn(null);

        assertTrue(sdk.queryPlans("ACTIVE").isEmpty());
    }

    @Test
    void queryPlans_blankStatusFilterTreatedAsNoFilterAndExcludesFinished() {
        QcmPlan active = plan(1L, "ACTIVE", "DAILY", "{\"hour\":1,\"minute\":0}");
        QcmPlan finished = plan(2L, "FINISHED", "ONCE", "{\"onceAt\":\"2026-01-01T00:00:00Z\"}");
        when(planService.selectList(any())).thenReturn(Arrays.asList(active, finished));

        List<SdkPlanSetting> result = sdk.queryPlans("   ");

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getPlanId());
        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planService).selectList(captor.capture());
        assertNull(captor.getValue().getStatus());
    }

    @Test
    void queryPlans_explicitFinishedFilterReturnsFinishedDisabled() {
        QcmPlan finished = plan(5L, "FINISHED", "ONCE", "{\"onceAt\":\"2026-01-01T00:00:00Z\"}");
        when(planService.selectList(any())).thenReturn(Collections.singletonList(finished));

        List<SdkPlanSetting> result = sdk.queryPlans("FINISHED");

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).getPlanId());
        assertEquals("FINISHED", result.get(0).getStatus());
        assertFalse(result.get(0).isEnabled());
    }

    @Test
    void queryPlans_mapsMonthlySchedule() {
        QcmPlan plan = plan(9L, "ACTIVE", "MONTHLY", "{\"hour\":6,\"minute\":15,\"monthDays\":[1,15,31]}");
        when(planService.selectList(any())).thenReturn(Collections.singletonList(plan));

        SdkPlanSetting s = sdk.queryPlans(null).get(0);

        assertEquals("MONTHLY", s.getScheduleType());
        assertEquals(6, s.getHour());
        assertEquals(15, s.getMinute());
        assertTrue(s.getMonthDays().containsAll(Arrays.asList(1, 15, 31)));
        assertNull(s.getWeekdays());
        assertNull(s.getOnceAt());
    }

    @Test
    void queryPlans_mapsOnceSchedule() {
        QcmPlan plan = plan(10L, "ACTIVE", "ONCE", "{\"onceAt\":\"2026-01-02T03:04:05Z\"}");
        when(planService.selectList(any())).thenReturn(Collections.singletonList(plan));

        SdkPlanSetting s = sdk.queryPlans(null).get(0);

        assertEquals("ONCE", s.getScheduleType());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), s.getOnceAt());
        assertNull(s.getWeekdays());
        assertNull(s.getMonthDays());
    }

    @Test
    void queryPlans_instrumentsBlankNullOrInvalidJsonYieldsEmptyList() {
        QcmPlan nullInstr = plan(1L, "ACTIVE", "DAILY", "{\"hour\":1,\"minute\":0}");
        nullInstr.setInstruments(null);
        QcmPlan blankInstr = plan(2L, "ACTIVE", "DAILY", "{\"hour\":1,\"minute\":0}");
        blankInstr.setInstruments("   ");
        QcmPlan invalidInstr = plan(3L, "ACTIVE", "DAILY", "{\"hour\":1,\"minute\":0}");
        invalidInstr.setInstruments("not-a-json");
        when(planService.selectList(any())).thenReturn(Arrays.asList(nullInstr, blankInstr, invalidInstr));

        List<SdkPlanSetting> result = sdk.queryPlans(null);

        assertEquals(3, result.size());
        assertTrue(result.get(0).getInstruments().isEmpty());
        assertTrue(result.get(1).getInstruments().isEmpty());
        assertTrue(result.get(2).getInstruments().isEmpty());
    }

    @Test
    void queryPlans_nullPlanIdMapsToZero() {
        QcmPlan plan = plan(1L, "ACTIVE", "DAILY", "{\"hour\":1,\"minute\":0}");
        plan.setId(null);
        when(planService.selectList(any())).thenReturn(Collections.singletonList(plan));

        assertEquals(0L, sdk.queryPlans(null).get(0).getPlanId());
    }

    @Test
    void queryPlans_invalidScheduleConfigJsonSkipsPlan() {
        QcmPlan bad = plan(1L, "ACTIVE", "DAILY", "{not-json}");
        when(planService.selectList(any())).thenReturn(Collections.singletonList(bad));

        assertTrue(sdk.queryPlans(null).isEmpty());
    }

    private static QcmPlan plan(long id, String status, String scheduleType, String scheduleConfig) {
        QcmPlan p = new QcmPlan();
        p.setId(id);
        p.setPlanName("plan-" + id);
        p.setQcType("zero_check");
        p.setInstruments("[\"CO\"]");
        p.setScheduleType(scheduleType);
        p.setScheduleConfig(scheduleConfig);
        p.setStatus(status);
        return p;
    }
}
