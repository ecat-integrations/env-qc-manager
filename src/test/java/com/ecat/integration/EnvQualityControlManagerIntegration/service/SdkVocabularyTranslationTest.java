package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.api.ResultFilter;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkBatchState;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkDurationKey;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkExecutionResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkExecutionStatus;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkFailureReason;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkInstrument;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkOperator;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkOperatorSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkQcType;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkRecordDetail;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkRunningExecution;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkStopReply;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkStopRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordKeyParamMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPhaseMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPointMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.StopOutcome;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDK 闭域词汇重打型回归锁（§6 矩阵）：锁「存储形态 ↔ api 枚举」的翻译层。
 * 缺陷根因是旧契约把存储词汇直接漏给消费方——记录行落数字码（qcm_record.parameter 存 "1"、
 * quality_control_type 存 "1"），旧测试夹具却用字母名/类型名造数据，把泄漏掩护成了绿。
 * 本类夹具一律用生产存储形态造行，验证消费方在全部装配点拿到的都是 api 枚举、
 * 过滤条件下发的都是存储词汇，以及译不出时的 IAE 纪律与双枚举锁步。
 *
 * @author coffee
 */
class SdkVocabularyTranslationTest {

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

    /** 生产存储形态的记录行：parameter/quality_control_type 均为数字码（编排器 buildRecords 落库形态）。 */
    private static QcmRecord storedRow(long id, int statusCode) {
        QcmRecord row = new QcmRecord();
        row.setId(id);
        row.setBatchId("batch-v");
        row.setParameter("1");
        row.setQualityControlType("1");
        row.setExecutionStatus(statusCode);
        row.setTaskType(TriggerSource.REMOTE.getCode());
        row.setTriggerUser("lims-system");
        row.setStartTime(Instant.ofEpochMilli(1_700_000_000_000L));
        return row;
    }

    private static SdkTriggerRequest triggerRequest(SdkOperator operator) {
        return SdkTriggerRequest.builder()
                .qcType(SdkQcType.SPAN_CHECK)
                .instruments(Collections.singletonList(SdkInstrument.SO2))
                .concentrationPpb(BigDecimal.valueOf(400))
                .operator(operator)
                .allowQueue(false)
                .build();
    }

    // ===== 往返一致（本次缺陷回归锁）：存储码进，api 枚举出，所有装配点一个不漏 =====

    @Test
    void storedCodesSurfaceAsApiEnumsAtEveryAssemblyPoint() {
        QcmRecord row = storedRow(31L, 1);
        when(orchestrator.runningBatchId()).thenReturn("batch-v");
        when(recordMapper.selectByBatchId("batch-v")).thenReturn(new ArrayList<>(Collections.singletonList(row)));
        when(orchestrator.stopExecution(isNull(), eq("batch-v"), isNull(), eq(false), eq("lims-system")))
                .thenReturn(StopOutcome.initiated("质控记录已中止", "batch-v", Collections.singletonList(31L)));
        when(recordService.selectQcmRecordById(31L)).thenReturn(row);
        when(phaseMapper.selectByRecordId(31L)).thenReturn(Collections.emptyList());
        when(keyParamMapper.selectByRecordId(31L)).thenReturn(Collections.emptyList());
        when(pointMapper.selectByRecordId(31L)).thenReturn(Collections.emptyList());

        List<SdkRunningExecution> running = sdk.queryRunning();
        SdkBatchState state = sdk.queryExecution("batch-v");
        SdkStopReply stopReply = sdk.stop(SdkStopRequest.builder().batchId("batch-v")
                .operator(SdkOperator.builder().sourceType(SdkOperatorSource.SDK_LOCAL).name("lims-system").build())
                .build());
        SdkExecutionResult result = sdk.getExecutionResult(31L);
        SdkRecordDetail detail = sdk.getRecordDetail(31L);

        assertEquals(Collections.singletonList(SdkInstrument.SO2), running.get(0).getInstruments());
        assertEquals(SdkQcType.SPAN_CHECK, running.get(0).getQcType());
        assertEquals(SdkTriggerSource.REMOTE, running.get(0).getTriggerSource());

        assertEquals(SdkInstrument.SO2, state.getRecords().get(0).getInstrument());
        assertEquals(SdkExecutionStatus.RUNNING, state.getRecords().get(0).getStatusName());
        assertEquals(1, state.getRecords().get(0).getStatus());

        assertEquals(Collections.singletonList(SdkInstrument.SO2), stopReply.getInstruments());
        assertEquals(SdkQcType.SPAN_CHECK, stopReply.getQcType());

        assertEquals(SdkInstrument.SO2, result.getInstrument());
        assertEquals(SdkQcType.SPAN_CHECK, result.getQcType());
        assertEquals(SdkTriggerSource.REMOTE, result.getTriggerSource());
        assertEquals(SdkInstrument.SO2, detail.getInstrument());
        assertEquals(SdkQcType.SPAN_CHECK, detail.getQcType());
    }

    /** 过滤条件下发存储词汇：仪器/质控类型都给枚举，SQL 拿到的是记录侧码 "1"
     *  （旧契约传字母名查空的陷阱回归锁）。方向对账最终以真库符合性 IT 为准——本用例的 mock
     *  曾把 qcType 错钉成计划侧 name "span_check"，mock 层校验不了记录列的真实存储词汇。 */
    @Test
    void resultFilterEnumsAreTranslatedToStoredVocabularyBeforeSql() {
        when(recordMapper.selectByFilter(any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Collections.emptyList());

        sdk.queryResults(ResultFilter.builder()
                .instrument(SdkInstrument.SO2)
                .qcType(SdkQcType.SPAN_CHECK)
                .triggerSource(SdkTriggerSource.REMOTE)
                .build());

        verify(recordMapper).selectByFilter(isNull(), isNull(), eq("1"), eq("1"),
                eq(TriggerSource.REMOTE.getCode()), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq(QualityControlSdkImpl.QUERY_RESULT_LIMIT + 1));
    }

    /** 触发入参枚举 → 内部词汇（编排器请求与快照同形态，内部域零改动）。 */
    @Test
    void triggerEnumsAreTranslatedToInternalVocabularyInRequestAndSnapshot() {
        when(orchestrator.triggerExecution(any(), eq(TriggerSource.REMOTE), eq("lims-system")))
                .thenReturn(BatchResult.accepted("batch-v", Collections.singletonList(31L)));

        sdk.trigger(SdkTriggerRequest.builder()
                .qcType(SdkQcType.SPAN_CHECK)
                .instruments(Collections.singletonList(SdkInstrument.SO2))
                .concentrationPpb(BigDecimal.valueOf(400))
                .durationOverrides(Collections.singletonMap(SdkDurationKey.STABLE_TIME_SECONDS, 30))
                .operator(SdkOperator.builder().sourceType(SdkOperatorSource.SDK_LOCAL).name("lims-system").build())
                .allowQueue(false)
                .build());

        ArgumentCaptor<QcExecutionRequest> captor = ArgumentCaptor.forClass(QcExecutionRequest.class);
        verify(orchestrator).triggerExecution(captor.capture(), eq(TriggerSource.REMOTE), eq("lims-system"));
        QcExecutionRequest req = captor.getValue();
        assertEquals("span_check", req.getQcType());
        assertEquals(Collections.singletonList("SO2"), req.getInstruments());
        Map<String, Object> overrides = req.getDurationOverrides();
        assertEquals(30, ((Number) overrides.get("stableTimeSeconds")).intValue());

        Map<String, Object> snapshot =
                com.ecat.integration.EnvQualityControlManagerIntegration.util.JsonUtils.parseMap(
                        req.getPlanSnapshotJson(), String.class, Object.class);
        assertEquals("span_check", snapshot.get("qcType"));
        assertEquals(Collections.singletonList("SO2"), snapshot.get("instruments"));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotOverrides = (Map<String, Object>) snapshot.get("durationOverrides");
        assertEquals(30, ((Number) snapshotOverrides.get("stableTimeSeconds")).intValue());
    }

    // ===== 未知值（R4）：译不出抛 IAE 带原值，禁伪造 =====

    @Test
    void unknownStoredInstrumentCodeThrowsWithRawCode() {
        QcmRecord row = storedRow(41L, 1);
        row.setParameter("99");
        when(orchestrator.runningBatchId()).thenReturn("batch-v");
        when(recordMapper.selectByBatchId("batch-v")).thenReturn(Collections.singletonList(row));
        when(recordService.selectQcmRecordById(41L)).thenReturn(row);

        IllegalArgumentException fromRunning = assertThrows(IllegalArgumentException.class,
                () -> sdk.queryRunning());
        assertTrue(fromRunning.getMessage().contains("99"), fromRunning.getMessage());

        IllegalArgumentException fromResult = assertThrows(IllegalArgumentException.class,
                () -> sdk.getExecutionResult(41L));
        assertTrue(fromResult.getMessage().contains("99"), fromResult.getMessage());
    }

    @Test
    void unknownStoredQcTypeCodeThrowsWithRawValue() {
        QcmRecord row = storedRow(42L, 2);
        row.setQualityControlType("42");
        when(recordService.selectQcmRecordById(42L)).thenReturn(row);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sdk.getExecutionResult(42L));
        assertTrue(ex.getMessage().contains("42"), ex.getMessage());
    }

    @Test
    void unknownFailureReasonValueThrowsWithRawValue() {
        QcmRecord row = storedRow(43L, 3);
        row.setFailureReason("SOMETHING_ELSE");
        when(recordService.selectQcmRecordById(43L)).thenReturn(row);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sdk.getExecutionResult(43L));
        assertTrue(ex.getMessage().contains("SOMETHING_ELSE"), ex.getMessage());
    }

    /** 落库 failure_reason 值集逐一映射；null（运行中/成功/启动期失败）如实透传 null。 */
    @Test
    void storedFailureReasonValuesMapToEnumAndNullStaysNull() {
        assertEquals(SdkFailureReason.EXECUTOR_BUSY_CONFLICT,
                failureReasonOfRow("EXECUTOR_BUSY_CONFLICT"));
        assertEquals(SdkFailureReason.EXECUTOR_TYPE_NOT_READY,
                failureReasonOfRow("EXECUTOR_TYPE_NOT_READY"));
        assertEquals(SdkFailureReason.INVALID_PARAM, failureReasonOfRow("INVALID_PARAM"));
        assertEquals(SdkFailureReason.QUEUE_NOT_SUPPORTED, failureReasonOfRow("QUEUE_NOT_SUPPORTED"));
        assertNull(failureReasonOfRow(null));
    }

    private SdkFailureReason failureReasonOfRow(String stored) {
        QcmRecord row = storedRow(44L, 3);
        row.setFailureReason(stored);
        when(recordService.selectQcmRecordById(44L)).thenReturn(row);
        return sdk.getExecutionResult(44L).getFailureReason();
    }

    // ===== 时长键：枚举层闭域 + 与校验器白名单锁步 =====

    @Test
    void unknownDurationKeyNameIsRejectedByTheEnumBoundary() {
        assertThrows(IllegalArgumentException.class, () -> SdkDurationKey.valueOf("NOT_A_KEY"));
    }

    /** api 时长键 ↔ 校验器 14 键白名单锁步：键名或值规则任何一侧漂移，本测试即红。 */
    @Test
    void everyDurationKeyIsAcceptedByTheSharedValidatorWhitelist() {
        PlanParamValidator validator = new PlanParamValidator();
        for (SdkDurationKey key : SdkDurationKey.values()) {
            PlanSaveDto dto = new PlanSaveDto();
            dto.setQcType(QualityControlTypeEnum.SPAN_CHECK.getName());
            dto.setInstruments(Collections.singletonList("SO2"));
            dto.setConcentrationPpb(BigDecimal.valueOf(400));
            dto.setDurationOverrides(Collections.<String, Object>singletonMap(
                    key.getKey(), isPercentKey(key) ? Arrays.asList(0f, 1f) : 1));

            List<String> errors = validator.validateExecutionParams(dto);

            assertTrue(errors.isEmpty(), "时长键 " + key + "（内部键 " + key.getKey() + "）未通过校验器: " + errors);
        }
        assertEquals(14, SdkDurationKey.values().length);
    }

    private static boolean isPercentKey(SdkDurationKey key) {
        return key == SdkDurationKey.MULTI_POINT_PERCENTS || key == SdkDurationKey.ACCURACY_POINT_PERCENTS;
    }

    // ===== 双枚举锁步：api 词汇域与内部枚举同步扩充，翻译层才可能译得出 =====

    @Test
    void apiVocabularyStaysInLockstepWithInternalEnums() {
        assertEquals(names(QualityControlTypeEnum.values()), names(SdkQcType.values()),
                "SdkQcType 与 QualityControlTypeEnum 常量失步");
        assertEquals(names(ParameterEnum.values()), names(SdkInstrument.values()),
                "SdkInstrument 与 ParameterEnum 常量失步");
        assertEquals(names(ExecutionStatusEnum.values()), names(SdkExecutionStatus.values()),
                "SdkExecutionStatus 与 ExecutionStatusEnum 常量失步");
        assertEquals(names(TriggerSource.values()), names(SdkTriggerSource.values()),
                "SdkTriggerSource 与 TriggerSource 常量失步");
        assertEquals(names(ScheduleType.values()), names(com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkScheduleType.values()),
                "SdkScheduleType 与 ScheduleType 常量失步");
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> out = new ArrayList<>();
        for (Enum<?> e : values) {
            out.add(e.name());
        }
        return out;
    }

    // ===== PM2_5 特例：内部展示名带点（PM2.5），翻译必须按常量名而非展示名往返 =====

    @Test
    void pm25DisplayValueWithDotRoundTripsThroughConstantName() {
        QcmRecord row = storedRow(51L, 2);
        row.setParameter("6");
        when(recordService.selectQcmRecordById(51L)).thenReturn(row);
        when(phaseMapper.selectByRecordId(51L)).thenReturn(Collections.emptyList());
        when(keyParamMapper.selectByRecordId(51L)).thenReturn(Collections.emptyList());
        when(pointMapper.selectByRecordId(51L)).thenReturn(Collections.emptyList());

        SdkExecutionResult result = sdk.getExecutionResult(51L);

        assertEquals(SdkInstrument.PM2_5, result.getInstrument());
        assertNotNull(ParameterEnum.PM2_5);
        assertEquals("PM2.5", ParameterEnum.PM2_5.getName());
    }
}
