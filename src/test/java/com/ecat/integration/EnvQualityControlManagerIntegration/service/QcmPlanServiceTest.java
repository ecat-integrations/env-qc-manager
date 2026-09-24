package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.Utils.DateTimeUtils;
import com.ecat.integration.EnvCalibrationComposerIntegration.EnvCalibrationComposerIntegration;
import com.github.pagehelper.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorType;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanEstimateDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanEstimateResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.QcmPlanScheduler;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.impl.QcmPlanServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划服务状态机 / 保存重算 / 立即执行共用装配 / 预估只读 用例：
 * mapper/scheduler/orchestrator 全 mock，固定时钟，零 sleep。
 *
 * @author coffee
 */
class QcmPlanServiceTest {

    /** 固定时钟：2026-08-21T00:00:00Z（东八区 08:00），DAILY 02:00 的下次触发=次日 02:00 沪。 */
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant DAILY_NEXT = Instant.parse("2026-08-21T18:00:00Z");

    private QcmPlanMapper planMapper;
    private QcmPlanScheduler scheduler;
    private QcmExecutionOrchestrator orchestrator;
    private EcatCore core;
    private IntegrationRegistry registry;
    private EnvCalibrationComposerIntegration composer;
    private IQcmPlanService service;

    @BeforeEach
    void setUp() {
        planMapper = mock(QcmPlanMapper.class);
        scheduler = mock(QcmPlanScheduler.class);
        orchestrator = mock(QcmExecutionOrchestrator.class);
        core = mock(EcatCore.class);
        registry = mock(IntegrationRegistry.class);
        composer = mock(EnvCalibrationComposerIntegration.class);
        when(core.getIntegrationRegistry()).thenReturn(registry);
        when(registry.getIntegration("integration-env-calibration-composer")).thenReturn(composer);
        Clock clock = Clock.fixed(NOW, ZONE);
        service = new QcmPlanServiceImpl(planMapper, scheduler, orchestrator,
                new PlanParamValidator(clock), core, clock);
        // 调度计算/摘要/锚点日派生的时区源 = ecat 平台时区（DateTimeUtils 单例），
        // 显式钉住与固定时钟同 zone，保证断言不依赖运行机器时区
        DateTimeUtils.setZone(ZONE);
    }

    @AfterEach
    void restorePlatformZone() {
        DateTimeUtils.setZone(ZoneId.systemDefault());
    }

    private static PlanSaveDto validDto() {
        PlanSaveDto dto = new PlanSaveDto();
        dto.setPlanName("城区站零点计划");
        dto.setQcType("zero_check");
        dto.setInstruments(Collections.singletonList("SO2"));
        dto.setScheduleType("DAILY");
        dto.setHour(2);
        dto.setMinute(0);
        return dto;
    }

    private static QcmPlan planRow(long id, String status) {
        QcmPlan plan = new QcmPlan();
        plan.setId(id);
        plan.setPlanName("城区站零点计划");
        plan.setQcType("zero_check");
        plan.setInstruments("[\"SO2\"]");
        plan.setScheduleType("DAILY");
        plan.setScheduleConfig("{\"hour\":2,\"minute\":0}");
        plan.setStatus(status);
        return plan;
    }

    /** 断言用：装配产物 schedule_config JSON 解析（坏 JSON 直接失败，测试数据问题立即可见）。 */
    private static JsonNode readConfig(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试装配的 schedule_config 非法: " + json, e);
        }
    }

    // ---------- 保存 ----------

    @Test
    void createDefaultsActiveAndComputesNextFire() {
        PlanSaveDto dto = validDto();
        when(planMapper.insert(any(QcmPlan.class))).thenAnswer(inv -> {
            inv.getArgument(0, QcmPlan.class).setId(9L);
            return 1;
        });
        when(planMapper.selectById(9L)).thenAnswer(inv -> planRow(9L, "ACTIVE"));

        QcmPlan saved = service.save(dto, "alice");

        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).insert(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        assertEquals("alice", captor.getValue().getCreatedBy());
        assertEquals(DAILY_NEXT, captor.getValue().getNextFireTime());
        verify(scheduler).notifyPlanChanged(9L);
        verify(orchestrator, never()).triggerExecution(any(), any(), anyString());
        assertNotNull(saved);
    }

    @Test
    void createValidationFailureThrowsWithJoinedErrorsAndSkipsInsert() {
        PlanSaveDto dto = validDto();
        dto.setPlanName("");
        dto.setHour(24);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(dto, "alice"));
        assertTrue(ex.getMessage().contains("计划名称不能为空") && ex.getMessage().contains("小时必须是 0-23"));
        verify(planMapper, never()).insert(any());
    }

    @Test
    void createOnceImmediateTriggersOrchestratorManually() {
        PlanSaveDto dto = validDto();
        dto.setScheduleType("ONCE");
        dto.setOnceMode("IMMEDIATE");
        when(planMapper.insert(any(QcmPlan.class))).thenAnswer(inv -> {
            inv.getArgument(0, QcmPlan.class).setId(9L);
            return 1;
        });
        when(planMapper.selectById(9L)).thenAnswer(inv -> planRow(9L, "ACTIVE"));
        when(orchestrator.triggerExecution(any(), eq(TriggerSource.MANUAL), eq("alice")))
                .thenReturn(BatchResult.accepted("b", Collections.singletonList(1L)));

        service.save(dto, "alice");

        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).insert(captor.capture());
        assertNull(captor.getValue().getNextFireTime());
        ArgumentCaptor<QcExecutionRequest> req = ArgumentCaptor.forClass(QcExecutionRequest.class);
        verify(orchestrator).triggerExecution(req.capture(), eq(TriggerSource.MANUAL), eq("alice"));
        assertEquals(Long.valueOf(9L), req.getValue().getPlanId());
        assertEquals("zero_check", req.getValue().getQcType());
    }

    @Test
    void intervalPlanStartWithOffsetKeepsAnchorDayUnshifted() {
        // e2e 缺陷复刻（墙钟冒充 UTC 被 +8h 漂日）：16:34+08:00 的锚点墙钟日必须是 09-23，
        // 摘要「起09-23」、首火 09-23 16:34 沪 = 08:34Z，均不得漂到次日
        PlanSaveDto dto = validDto();
        dto.setScheduleType("INTERVAL");
        dto.setIntervalDays(2);
        dto.setHour(16);
        dto.setMinute(34);
        dto.setPlanStartTime("2026-09-23T16:34:00+08:00");
        when(planMapper.insert(any(QcmPlan.class))).thenAnswer(inv -> {
            inv.getArgument(0, QcmPlan.class).setId(21L);
            return 1;
        });

        service.save(dto, "alice");

        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).insert(captor.capture());
        QcmPlan saved = captor.getValue();
        assertEquals(Instant.parse("2026-09-23T08:34:00Z"), saved.getPlanStartTime());
        JsonNode config = readConfig(saved.getScheduleConfig());
        assertEquals("2026-09-23T08:34:00Z", config.path("anchorDate").asText());
        assertEquals(Instant.parse("2026-09-23T08:34:00Z"), saved.getNextFireTime());
        assertEquals("每 2 天 16:34 起09-23", service.scheduleSummary(saved));
    }

    @Test
    void onceAtStoredAsUtcInstantInConfig() {
        // config 内嵌时刻统一存转换后的 UTC instant 串（与 anchorDate 同形态），
        // ScheduleSpecs 按 Instant.parse 读——偏移原串不得入库
        PlanSaveDto dto = validDto();
        dto.setScheduleType("ONCE");
        dto.setOnceMode("SCHEDULED");
        dto.setOnceAt("2026-08-22T11:00:00+08:00");
        when(planMapper.insert(any(QcmPlan.class))).thenAnswer(inv -> {
            inv.getArgument(0, QcmPlan.class).setId(22L);
            return 1;
        });

        service.save(dto, "alice");

        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).insert(captor.capture());
        JsonNode config = readConfig(captor.getValue().getScheduleConfig());
        assertEquals("2026-08-22T03:00:00Z", config.path("onceAt").asText());
    }

    @Test
    void createOnceScheduledNextFireEqualsOnceAt() {
        PlanSaveDto dto = validDto();
        dto.setScheduleType("ONCE");
        dto.setOnceMode("SCHEDULED");
        // RFC 3339 带偏移形态（墙钟 11:00 东八 = 03:00Z）：API 契约接受偏移与 Z 两种标准形态
        dto.setOnceAt("2026-08-22T11:00:00+08:00");
        when(planMapper.insert(any(QcmPlan.class))).thenAnswer(inv -> {
            inv.getArgument(0, QcmPlan.class).setId(9L);
            return 1;
        });
        when(planMapper.selectById(9L)).thenAnswer(inv -> planRow(9L, "ACTIVE"));

        service.save(dto, "alice");

        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).insert(captor.capture());
        assertEquals(Instant.parse("2026-08-22T03:00:00Z"), captor.getValue().getNextFireTime());
        verify(orchestrator, never()).triggerExecution(any(), any(), anyString());
    }

    @Test
    void editPausedPlanKeepsNextNullAndFullReplaces() {
        PlanSaveDto dto = validDto();
        dto.setId(5L);
        when(planMapper.selectById(5L)).thenReturn(planRow(5L, "PAUSED"));

        service.save(dto, "bob");

        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).updateAll(captor.capture());
        assertEquals("PAUSED", captor.getValue().getStatus());
        assertNull(captor.getValue().getNextFireTime());
        assertEquals("bob", captor.getValue().getUpdatedBy());
        verify(scheduler).notifyPlanChanged(5L);
        verify(planMapper, never()).insert(any());
    }

    @Test
    void editActivePlanRecomputesNextFire() {
        PlanSaveDto dto = validDto();
        dto.setId(5L);
        when(planMapper.selectById(5L)).thenReturn(planRow(5L, "ACTIVE"));

        service.save(dto, "bob");

        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).updateAll(captor.capture());
        assertEquals(DAILY_NEXT, captor.getValue().getNextFireTime());
    }

    @Test
    void editFinishedPlanRejected() {
        PlanSaveDto dto = validDto();
        dto.setId(5L);
        when(planMapper.selectById(5L)).thenReturn(planRow(5L, "FINISHED"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(dto, "bob"));
        assertTrue(ex.getMessage().contains("不可编辑"));
        verify(planMapper, never()).updateAll(any());
    }

    @Test
    void saveMissingPlanOnEditRejected() {
        PlanSaveDto dto = validDto();
        dto.setId(404L);
        when(planMapper.selectById(404L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.save(dto, "bob"));
    }

    // ---------- 状态机 ----------

    @Test
    void pauseClearsNextFireAndNotifies() {
        when(planMapper.selectById(5L)).thenReturn(planRow(5L, "ACTIVE"));
        service.changeStatus(5L, "PAUSED", "bob");
        verify(planMapper).updateStatus(5L, "PAUSED", "bob");
        verify(planMapper).updateNextFireTime(5L, null, null);
        verify(scheduler).notifyPlanChanged(5L);
    }

    @Test
    void resumeRecomputesNextFireFromNow() {
        when(planMapper.selectById(5L)).thenReturn(planRow(5L, "PAUSED"));
        service.changeStatus(5L, "ACTIVE", "bob");
        verify(planMapper).updateStatus(5L, "ACTIVE", "bob");
        verify(planMapper).updateNextFireTime(5L, DAILY_NEXT, null);
        verify(scheduler).notifyPlanChanged(5L);
    }

    @Test
    void finishedPlanCannotPauseOrResume() {
        when(planMapper.selectById(5L)).thenReturn(planRow(5L, "FINISHED"));
        assertThrows(IllegalArgumentException.class, () -> service.changeStatus(5L, "PAUSED", "bob"));
        assertThrows(IllegalArgumentException.class, () -> service.changeStatus(5L, "ACTIVE", "bob"));
        verify(planMapper, never()).updateStatus(any(), anyString(), anyString());
    }

    @Test
    void sameStateTransitionRejected() {
        when(planMapper.selectById(5L)).thenReturn(planRow(5L, "ACTIVE"));
        assertThrows(IllegalArgumentException.class, () -> service.changeStatus(5L, "ACTIVE", "bob"));
        verify(planMapper, never()).updateStatus(any(), anyString(), anyString());
    }

    @Test
    void invalidTargetStatusRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.changeStatus(5L, "FINISHED", "bob"));
    }

    // ---------- 删除 ----------

    @Test
    void deleteRemovesRowAndNotifiesEvenFinished() {
        when(planMapper.selectById(5L)).thenReturn(planRow(5L, "FINISHED"));
        service.delete(5L, "bob");
        verify(planMapper).deleteById(5L);
        verify(scheduler).notifyPlanChanged(5L);
    }

    @Test
    void deleteMissingPlanRejected() {
        when(planMapper.selectById(5L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.delete(5L, "bob"));
        verify(planMapper, never()).deleteById(any());
    }

    // ---------- 立即执行 ----------

    @Test
    void runNowAssemblesRequestFromPlanRowAndTriggersManual() {
        QcmPlan plan = planRow(7L, "ACTIVE");
        plan.setConcentrationPpb(null);
        when(planMapper.selectById(7L)).thenReturn(plan);
        BatchResult expected = BatchResult.accepted("b", Collections.singletonList(1L));
        when(orchestrator.triggerExecution(any(), eq(TriggerSource.MANUAL), eq("bob"))).thenReturn(expected);

        BatchResult result = service.runNow(7L, "bob");

        assertEquals(expected, result);
        ArgumentCaptor<QcExecutionRequest> req = ArgumentCaptor.forClass(QcExecutionRequest.class);
        verify(orchestrator).triggerExecution(req.capture(), eq(TriggerSource.MANUAL), eq("bob"));
        assertEquals(Long.valueOf(7L), req.getValue().getPlanId());
        assertEquals(Collections.singletonList("SO2"), req.getValue().getInstruments());
        assertNotNull(req.getValue().getPlanSnapshotJson());
    }

    @Test
    void runNowFinishedRejected() {
        when(planMapper.selectById(7L)).thenReturn(planRow(7L, "FINISHED"));
        assertThrows(IllegalArgumentException.class, () -> service.runNow(7L, "bob"));
        verify(orchestrator, never()).triggerExecution(any(), any(), anyString());
    }

    @Test
    void runNowMissingCallerRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.runNow(7L, " "));
    }

    // ---------- 调度摘要人话格式（统一调度模型：每 N 天 / 每 N 周 / 每月 / 一次性） ----------

    @Test
    void scheduleSummaryFourFormats() {
        QcmPlan daily = planRow(1L, "ACTIVE");
        // 存量 DAILY 行为不动，仅展示层译作「每 1 天」
        assertEquals("每 1 天 02:00", service.scheduleSummary(daily));

        QcmPlan weekly = planRow(2L, "ACTIVE");
        weekly.setScheduleType("WEEKLY");
        weekly.setScheduleConfig("{\"hour\":2,\"minute\":0,\"weekdays\":[3,1]}");
        assertEquals("每 1 周 周一、周三 02:00", service.scheduleSummary(weekly));

        QcmPlan monthly = planRow(3L, "ACTIVE");
        monthly.setScheduleType("MONTHLY");
        monthly.setScheduleConfig("{\"hour\":2,\"minute\":0,\"monthDays\":[15,1]}");
        assertEquals("每月 1·15 日 02:00", service.scheduleSummary(monthly));

        QcmPlan immediate = planRow(4L, "ACTIVE");
        immediate.setScheduleType("ONCE");
        immediate.setScheduleConfig("{\"onceMode\":\"IMMEDIATE\"}");
        assertEquals("一次性 · 立刻执行", service.scheduleSummary(immediate));

        QcmPlan scheduled = planRow(5L, "ACTIVE");
        scheduled.setScheduleType("ONCE");
        // 2026-08-22T03:00:00Z = 东八区 11:00（00:00Z+8=08:00 → 03:00Z=11:00）
        scheduled.setScheduleConfig("{\"onceMode\":\"SCHEDULED\",\"onceAt\":\"2026-08-22T03:00:00Z\"}");
        assertEquals("一次性 08-22 11:00", service.scheduleSummary(scheduled));
    }

    @Test
    void scheduleSummaryAppendsPlanStartWhenPresent() {
        // 「起MM-DD」仅有效期起有值才拼（东八区墙钟日）；隔周与按天同样适用
        QcmPlan interval = planRow(6L, "ACTIVE");
        interval.setScheduleType("INTERVAL");
        interval.setScheduleConfig(
                "{\"hour\":2,\"minute\":45,\"intervalDays\":2,\"anchorDate\":\"2026-08-21T00:00:00Z\"}");
        interval.setPlanStartTime(Instant.parse("2026-09-23T16:00:00Z")); // 东八区 09-24 00:00
        assertEquals("每 2 天 02:45 起09-24", service.scheduleSummary(interval));

        QcmPlan weekly = planRow(7L, "ACTIVE");
        weekly.setScheduleType("WEEKLY");
        weekly.setScheduleConfig("{\"hour\":9,\"minute\":0,\"weekdays\":[3],\"intervalWeeks\":2}");
        weekly.setPlanStartTime(Instant.parse("2026-09-30T16:00:00Z")); // 东八区 10-01 00:00
        assertEquals("每 2 周 周三 09:00 起10-01", service.scheduleSummary(weekly));

        // 无有效期起不拼「起」段（存量 WEEKLY 无 intervalWeeks 键亦按每 1 周展示）
        QcmPlan noStart = planRow(8L, "ACTIVE");
        noStart.setScheduleType("WEEKLY");
        noStart.setScheduleConfig("{\"hour\":9,\"minute\":0,\"weekdays\":[3]}");
        assertEquals("每 1 周 周三 09:00", service.scheduleSummary(noStart));
    }

    // ---------- INTERVAL 装配与 03 §7.4 新列（校准策略/同日优先级） ----------

    @Test
    void intervalPausedEditWritesServerAnchorAndPolicyColumns() {
        // PAUSED 编辑不触调度计算（INTERVAL nextFire 计算是 #42 范围），恰好单测 #41 的装配面
        PlanSaveDto dto = validDto();
        dto.setId(5L);
        dto.setScheduleType("INTERVAL");
        dto.setIntervalDays(2);
        dto.setPlanStartTime("2026-09-24T09:00:00+08:00");
        dto.setHour(2);
        dto.setMinute(45);
        dto.setCalibrationPolicy("CALIBRATE_LOW_DRIFT");
        dto.setSameDayPriority("LOW");
        when(planMapper.selectById(5L)).thenReturn(planRow(5L, "PAUSED"));

        service.save(dto, "bob");

        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).updateAll(captor.capture());
        QcmPlan saved = captor.getValue();
        assertEquals("INTERVAL", saved.getScheduleType());
        JsonNode config = readConfig(saved.getScheduleConfig());
        assertEquals(2, config.path("intervalDays").asInt());
        assertEquals(2, config.path("hour").asInt());
        assertEquals(45, config.path("minute").asInt());
        // 锚点=有效期起墙钟日（服务端派生写入 planStart 原值，calculator 只取 zone 墙钟日；
        // 「保存当日」隐式锚已废除，编辑改 planStart 即重锚）
        assertEquals("2026-09-24T01:00:00Z", config.path("anchorDate").asText());
        assertEquals("CALIBRATE_LOW_DRIFT", saved.getCalibrationPolicy());
        assertEquals("LOW", saved.getSameDayPriority());
        assertNull(saved.getNextFireTime());
    }

    @Test
    void saveWithoutPolicyAndPriorityPersistsNullColumns() {
        PlanSaveDto dto = validDto();
        when(planMapper.insert(any(QcmPlan.class))).thenAnswer(inv -> {
            inv.getArgument(0, QcmPlan.class).setId(9L);
            return 1;
        });
        when(planMapper.selectById(9L)).thenAnswer(inv -> planRow(9L, "ACTIVE"));

        service.save(dto, "alice");

        // 未提供即落 NULL 列（NULL=STANDARD / NULL=NONE 由列语义承载，不落空串）
        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).insert(captor.capture());
        assertNull(captor.getValue().getCalibrationPolicy());
        assertNull(captor.getValue().getSameDayPriority());
    }

    @Test
    void scheduleSummaryIntervalRendersEveryNDays() {
        QcmPlan interval = planRow(6L, "ACTIVE");
        interval.setScheduleType("INTERVAL");
        interval.setScheduleConfig(
                "{\"hour\":2,\"minute\":45,\"intervalDays\":2,\"anchorDate\":\"2026-08-21T00:00:00Z\"}");
        assertEquals("每 2 天 02:45", service.scheduleSummary(interval));
    }

    @Test
    void scheduleSummaryIntervalMissingDaysThrows() {
        QcmPlan broken = planRow(6L, "ACTIVE");
        broken.setScheduleType("INTERVAL");
        broken.setScheduleConfig("{\"hour\":2,\"minute\":45}");
        assertThrows(IllegalArgumentException.class, () -> service.scheduleSummary(broken));
    }

    // ---------- 预估（只读） ----------

    @Test
    void estimateUsesComposerReadOnlyPhasesAndNeverExecutes() {
        PlanEstimateDto dto = new PlanEstimateDto();
        dto.setQcType("zero_check");
        dto.setInstruments(Collections.singletonList("NO2"));
        when(composer.estimatePhases(eq(ExecutorType.getEnum("air.monitor.calibration.zero_check")),
                eq("no"), any()))
                .thenReturn(Arrays.asList(
                        new PhaseInfo("prepare", "安全初始化", 30),
                        new PhaseInfo("zero", "零点采集", 600),
                        new PhaseInfo("recovery", "设备恢复", 120)));

        PlanEstimateResult result = service.estimate(dto);

        assertEquals(3, result.getPhases().size());
        assertEquals("prepare", result.getPhases().get(0).getId());
        assertEquals(30, result.getPhases().get(0).getEstimatedSeconds());
        assertEquals(750L, result.getTotalEstimatedSeconds());
        assertEquals(120, result.getRecoverySeconds());
        verify(composer, never()).execute(any(), anyString());
        verify(composer, never()).execute(any(), anyString(), any());
        verify(planMapper, never()).insert(any());
    }

    @Test
    void estimateUnknownTypeOrMissingInstrumentRejected() {
        // 未知类型用真实非法值断言（multi_zero_check 在 composer 接线后已不是未知类型，
        // 旧断言系 pre-ExecutorType 接线语义，全 reactor 构建随 composer 交付翻红）
        PlanEstimateDto badType = new PlanEstimateDto();
        badType.setQcType("not_a_qc_type");
        badType.setInstruments(Collections.singletonList("SO2"));
        assertThrows(IllegalArgumentException.class, () -> service.estimate(badType));

        // 非 multi 类型多仪器仍拒绝：单仪器语义只对 multi_zero_check 放开（G5 multi 预估）
        PlanEstimateDto spanTwoInstruments = new PlanEstimateDto();
        spanTwoInstruments.setQcType("span_check");
        spanTwoInstruments.setInstruments(Arrays.asList("SO2", "NO2"));
        assertThrows(IllegalArgumentException.class, () -> service.estimate(spanTwoInstruments));

        PlanEstimateDto noInstrument = new PlanEstimateDto();
        noInstrument.setQcType("zero_check");
        assertThrows(IllegalArgumentException.class, () -> service.estimate(noInstrument));

        PlanEstimateDto multiEmpty = new PlanEstimateDto();
        multiEmpty.setQcType("multi_zero_check");
        assertThrows(IllegalArgumentException.class, () -> service.estimate(multiEmpty));
    }

    @Test
    void estimateMultiZeroCheckAcceptsUpToFourInstruments() {
        // G5：multi 计划预估——estimate 对 multi_zero_check 开多仪器（1~4 台），与
        // PlanParamValidator 多仪器白名单同口径；其余类型保持单仪器 API 语义
        PlanEstimateDto dto = new PlanEstimateDto();
        dto.setQcType("multi_zero_check");
        dto.setInstruments(Arrays.asList("O3", "CO", "NO2", "SO2"));
        when(composer.estimatePhases(eq(ExecutorType.getEnum("air.monitor.calibration.multi_zero_check")),
                anyString(), any()))
                .thenReturn(Arrays.asList(
                        new PhaseInfo("check", "同时零点核查", 1050),
                        new PhaseInfo("recovery", "设备恢复", 120)));

        PlanEstimateResult result = service.estimate(dto);

        assertEquals(2, result.getPhases().size());
        assertEquals(1170L, result.getTotalEstimatedSeconds());
        // flowParams 走 buildFlowParams MULTI 分支：gas key 列表直传（勿 JSON 化）、零点 0、流量缺省 5
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(composer).estimatePhases(eq(ExecutorType.getEnum("air.monitor.calibration.multi_zero_check")),
                anyString(), captor.capture());
        assertEquals(Arrays.asList("o3", "co", "no", "so2"), captor.getValue().get("instruments"));
        assertEquals(0f, captor.getValue().get("spanConcentrationPpb"));
        assertEquals(5f, captor.getValue().get("flowRateLpm"));
        // multi 无浓度点语义（零点浓度表不适配多气行），points 为空表
        assertTrue(result.getPoints().isEmpty());
    }

    // ---------- 预估浓度点（G-REQ-9）+ durationOverrides 透传（G-BUG-16） ----------

    @Test
    void estimateSpanCheckForwardsDurationOverridesToComposer() {
        PlanEstimateDto dto = new PlanEstimateDto();
        dto.setQcType("span_check");
        dto.setInstruments(Collections.singletonList("SO2"));
        dto.setConcentrationPpb(java.math.BigDecimal.valueOf(400));
        dto.setDurationOverrides(Collections.singletonMap("sampleCount", 20));
        when(composer.estimatePhases(eq(ExecutorType.getEnum("air.monitor.calibration.span_check")),
                eq("so2"), any())).thenReturn(Collections.emptyList());

        service.estimate(dto);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(composer).estimatePhases(eq(ExecutorType.getEnum("air.monitor.calibration.span_check")),
                eq("so2"), captor.capture());
        assertEquals(20, captor.getValue().get("sampleCount"));
        assertEquals(400f, captor.getValue().get("spanConcentrationPpb"));
    }

    @Test
    void estimateMultiCheckMapsComposerPointsWithCoUnitConversion() {
        PlanEstimateDto dto = new PlanEstimateDto();
        dto.setQcType("multi_check");
        dto.setInstruments(Collections.singletonList("CO"));
        when(composer.estimatePhases(any(), anyString(), any())).thenReturn(Collections.emptyList());
        when(composer.estimatePoints(eq(ExecutorType.getEnum("air.monitor.calibration.multi_check")),
                eq("co"), any()))
                .thenReturn(Arrays.asList(
                        new EnvCalibrationComposerIntegration.PointConcentration(0.0d, 0.0d),
                        new EnvCalibrationComposerIntegration.PointConcentration(0.8d, 40000.0d)));

        PlanEstimateResult result = service.estimate(dto);

        assertEquals(2, result.getPoints().size());
        assertEquals(0.0d, result.getPoints().get(0).getPercent(), 1e-9);
        assertEquals(0.0d, result.getPoints().get(0).getConcentrationPpb(), 1e-9);
        assertEquals("0", result.getPoints().get(0).getDisplayValue());
        assertEquals("ppm", result.getPoints().get(0).getUnit());
        assertEquals(0.8d, result.getPoints().get(1).getPercent(), 1e-9);
        assertEquals(40000.0d, result.getPoints().get(1).getConcentrationPpb(), 1e-9);
        assertEquals("40", result.getPoints().get(1).getDisplayValue());
        assertEquals("ppm", result.getPoints().get(1).getUnit());
    }

    @Test
    void estimateMultiCheckMapsComposerPointsNonCoInPpb() {
        PlanEstimateDto dto = new PlanEstimateDto();
        dto.setQcType("multi_check");
        dto.setInstruments(Collections.singletonList("SO2"));
        when(composer.estimatePhases(any(), anyString(), any())).thenReturn(Collections.emptyList());
        when(composer.estimatePoints(eq(ExecutorType.getEnum("air.monitor.calibration.multi_check")),
                eq("so2"), any()))
                .thenReturn(Collections.singletonList(
                        new EnvCalibrationComposerIntegration.PointConcentration(0.1d, 50.0d)));

        PlanEstimateResult result = service.estimate(dto);

        assertEquals(1, result.getPoints().size());
        assertEquals(50.0d, result.getPoints().get(0).getConcentrationPpb(), 1e-9);
        assertEquals("50", result.getPoints().get(0).getDisplayValue());
        assertEquals("ppb", result.getPoints().get(0).getUnit());
    }

    @Test
    void estimateAccuracyCheckAlsoRequestsPoints() {
        PlanEstimateDto dto = new PlanEstimateDto();
        dto.setQcType("accuracy_check");
        dto.setInstruments(Collections.singletonList("NO2"));
        when(composer.estimatePhases(any(), anyString(), any())).thenReturn(Collections.emptyList());
        when(composer.estimatePoints(eq(ExecutorType.getEnum("air.monitor.calibration.accuracy_check")),
                eq("no"), any())).thenReturn(Collections.emptyList());

        PlanEstimateResult result = service.estimate(dto);

        assertTrue(result.getPoints().isEmpty());
        verify(composer).estimatePoints(eq(ExecutorType.getEnum("air.monitor.calibration.accuracy_check")),
                eq("no"), any());
    }

    @Test
    void estimateZeroCheckSingleZeroPoint() {
        PlanEstimateDto dto = new PlanEstimateDto();
        dto.setQcType("zero_check");
        dto.setInstruments(Collections.singletonList("NO2"));
        when(composer.estimatePhases(any(), anyString(), any())).thenReturn(Collections.emptyList());

        PlanEstimateResult result = service.estimate(dto);

        assertEquals(1, result.getPoints().size());
        assertEquals(0.0d, result.getPoints().get(0).getPercent(), 1e-9);
        assertEquals(0.0d, result.getPoints().get(0).getConcentrationPpb(), 1e-9);
        assertEquals("0", result.getPoints().get(0).getDisplayValue());
        assertEquals("ppb", result.getPoints().get(0).getUnit());
        verify(composer, never()).estimatePoints(any(), anyString(), any());
    }

    @Test
    void estimateSpanCheckSinglePointFromFlowParams() {
        PlanEstimateDto dto = new PlanEstimateDto();
        dto.setQcType("span_check");
        dto.setInstruments(Collections.singletonList("SO2"));
        dto.setConcentrationPpb(java.math.BigDecimal.valueOf(400));
        when(composer.estimatePhases(any(), anyString(), any())).thenReturn(Collections.emptyList());

        PlanEstimateResult result = service.estimate(dto);

        assertEquals(1, result.getPoints().size());
        assertNull(result.getPoints().get(0).getPercent());
        assertEquals(400.0d, result.getPoints().get(0).getConcentrationPpb(), 1e-9);
        assertEquals("400", result.getPoints().get(0).getDisplayValue());
        assertEquals("ppb", result.getPoints().get(0).getUnit());
    }

    // ---------- 分页容器契约：PageHelper Page（承载 COUNT total）必须穿透 service 富化层 ----------

    /**
     * 生产形态：宿主 PageInterceptor 使 mapper 返回 PageHelper {@link Page}（承载 COUNT 出的
     * total）；ruoyi getDataTable 用 PageInfo(list).getTotal() 取总数，普通 ArrayList 退化为
     * list.size()=pageSize——前端总数错、按 total 判全量的清点漏行（total=页行数的根因）。
     * 契约：富化必须原地做、返回 mapper 容器引用（同仓 report findPage 惯例）。
     */
    @Test
    void selectList_preservesPageContainer_totalSurvivesDecoration() {
        Page<QcmPlan> page = new Page<>(2, 10);
        page.setTotal(29L);
        for (int i = 0; i < 10; i++) {
            page.add(planRow(100L + i, "ACTIVE"));
        }
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(page);

        List<QcmPlan> out = service.selectList(new QcmPlan());

        assertSame(page, out, "须原地富化并返回 mapper 容器引用（Page 携带 total），不得拷贝成新 List");
        assertEquals(29L, page.getTotal());
        assertEquals(10, out.size());
        assertNotNull(out.get(0).getScheduleSummary(), "行富化照常生效");
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
