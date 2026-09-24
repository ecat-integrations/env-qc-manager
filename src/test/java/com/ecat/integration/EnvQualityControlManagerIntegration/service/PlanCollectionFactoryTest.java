package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.CollectionCreateDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.CollectionCreateResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集合快捷方式工厂矩阵（03 设计 §6 单测层）：mock IQcmPlanService 与
 * 时长预估缝（manager 裁决：预警只依赖「行 → 秒数」契约），真 PlanParamValidator 配
 * 固定时钟；槽位链纯秒数算术，零 sleep 零真实调度。事务注解存在性以反射断言，
 * 真实 DB 回滚由 e2e 兜底。
 *
 * @author coffee
 */
class PlanCollectionFactoryTest {

    /** 固定时钟：2026-09-22T02:00:00Z（东八区 10:00 周二）。 */
    private static final Instant NOW = Instant.parse("2026-09-22T02:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private IQcmPlanService planService;
    private PlanRowDurationEstimator estimator;
    private PlanCollectionFactory factory;
    private final AtomicInteger idSeq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        planService = mock(IQcmPlanService.class);
        estimator = mock(PlanRowDurationEstimator.class);
        factory = new PlanCollectionFactory(planService, new PlanParamValidator(Clock.fixed(NOW, ZONE)), estimator);
        // 预估缝默认 600s（10 分钟）：模板槽间距 1 小时，600+900 缓冲不触预警
        when(estimator.estimateTotalSeconds(any(PlanSaveDto.class))).thenReturn(600L);
        when(planService.save(any(PlanSaveDto.class), anyString())).thenAnswer(inv -> {
            PlanSaveDto row = inv.getArgument(0);
            QcmPlan saved = new QcmPlan();
            saved.setId((long) idSeq.incrementAndGet());
            saved.setPlanName(row.getPlanName());
            return saved;
        });
    }

    /** 日常四气全勾（乱序输入验槽位序规整）；按天间隔调度，锚点日由 planStartTime 派生。 */
    private static CollectionCreateDto dailyDto() {
        CollectionCreateDto dto = new CollectionCreateDto();
        dto.setTemplate("DAILY");
        dto.setCollectionName("城区站日常核查");
        dto.setInstruments(Arrays.asList("SO2", "NO2", "CO", "O3"));
        dto.setScheduleType("INTERVAL");
        dto.setIntervalDays(2);
        dto.setPlanStartTime("2026-09-24T00:00:00Z");
        return dto;
    }

    private static CollectionCreateDto weeklyDto() {
        CollectionCreateDto dto = new CollectionCreateDto();
        dto.setTemplate("WEEKLY");
        dto.setCollectionName("城区站周核查");
        dto.setInstruments(Arrays.asList("O3", "CO", "NO2", "SO2"));
        dto.setScheduleType("WEEKLY");
        dto.setWeekdays(Collections.singletonList(3));
        return dto;
    }

    private static CollectionCreateDto.RowOverride rowOverride(String rowKey) {
        CollectionCreateDto.RowOverride override = new CollectionCreateDto.RowOverride();
        override.setRowKey(rowKey);
        return override;
    }

    private List<PlanSaveDto> savedRows(int expectedCount) {
        ArgumentCaptor<PlanSaveDto> captor = ArgumentCaptor.forClass(PlanSaveDto.class);
        verify(planService, times(expectedCount)).save(captor.capture(), anyString());
        return captor.getAllValues();
    }

    // ---------- 模板组装、命名、默认值 ----------

    @Test
    void dailyFourGasesAssemblesFiveRowsWithDefaults() {
        CollectionCreateResult result = factory.createCollection(dailyDto(), "admin");

        assertTrue(result.isCreated());
        assertEquals(5, result.getPlans().size());
        assertTrue(result.getWarnings().isEmpty());
        assertTrue(result.getEstimateFailures().isEmpty());

        List<PlanSaveDto> rows = savedRows(5);
        PlanSaveDto zero = rows.get(0);
        assertEquals("城区站日常核查-四气-零点", zero.getPlanName());
        assertEquals("multi_zero_check", zero.getQcType());
        // 零点 instruments 按槽位序规整（乱序输入不改行序与仪器序）
        assertEquals(Arrays.asList("O3", "CO", "NO2", "SO2"), zero.getInstruments());
        assertEquals(0, zero.getHour());
        assertEquals(45, zero.getMinute());
        assertEquals("INTERVAL", zero.getScheduleType());
        assertEquals(Integer.valueOf(2), zero.getIntervalDays());
        assertEquals("LOW", zero.getSameDayPriority());
        assertNull(zero.getCalibrationPolicy());

        String[] spanNames = {"O3", "CO", "NOx", "SO2"};
        int[] spanHours = {1, 2, 3, 4};
        String[] spanGases = {"O3", "CO", "NO2", "SO2"};
        for (int i = 0; i < 4; i++) {
            PlanSaveDto span = rows.get(i + 1);
            assertEquals("城区站日常核查-" + spanNames[i] + "-跨度", span.getPlanName());
            assertEquals("span_check", span.getQcType());
            assertEquals(Collections.singletonList(spanGases[i]), span.getInstruments());
            assertEquals(spanHours[i], span.getHour());
            assertEquals(45, span.getMinute());
            assertEquals("INTERVAL", span.getScheduleType());
            assertEquals(Integer.valueOf(2), span.getIntervalDays());
            assertEquals("LOW", span.getSameDayPriority());
            assertNull(span.getCalibrationPolicy());
            BigDecimal expected = "CO".equals(spanGases[i]) ? BigDecimal.valueOf(40000) : BigDecimal.valueOf(400);
            assertEquals(0, expected.compareTo(span.getConcentrationPpb()), spanNames[i]);
            assertNull(span.getFlowRateLpm());
        }
    }

    @Test
    void weeklyAppliesHighPriorityWeekdaysAndPolicy() {
        CollectionCreateDto dto = weeklyDto();
        dto.setCalibrationPolicy("CALIBRATE_LOW_DRIFT");
        CollectionCreateResult result = factory.createCollection(dto, "admin");

        assertTrue(result.isCreated());
        List<PlanSaveDto> rows = savedRows(5);
        assertEquals("城区站周核查-四气-零点", rows.get(0).getPlanName());
        for (PlanSaveDto row : rows) {
            assertEquals("WEEKLY", row.getScheduleType());
            assertEquals(Collections.singletonList(3), row.getWeekdays());
            assertNull(row.getIntervalDays());
            assertEquals("HIGH", row.getSameDayPriority());
            assertEquals("CALIBRATE_LOW_DRIFT", row.getCalibrationPolicy());
        }
    }

    @Test
    void threeGasesProduceFourRowsWithoutSlotShift() {
        // 勾 O3/NO2/SO2：CO 槽位缺席，NO2/SO2 跨度仍按 canonical 槽位 3:45/4:45，不移位
        CollectionCreateDto dto = dailyDto();
        dto.setInstruments(Arrays.asList("SO2", "NO2", "O3"));
        CollectionCreateResult result = factory.createCollection(dto, "admin");

        assertTrue(result.isCreated());
        List<PlanSaveDto> rows = savedRows(4);
        assertEquals("城区站日常核查-O3、NOx、SO2-零点", rows.get(0).getPlanName());
        assertEquals(Arrays.asList("O3", "NO2", "SO2"), rows.get(0).getInstruments());
        assertEquals("城区站日常核查-O3-跨度", rows.get(1).getPlanName());
        assertEquals(1, rows.get(1).getHour());
        assertEquals("城区站日常核查-NOx-跨度", rows.get(2).getPlanName());
        assertEquals(3, rows.get(2).getHour());
        assertEquals("城区站日常核查-SO2-跨度", rows.get(3).getPlanName());
        assertEquals(4, rows.get(3).getHour());
    }

    @Test
    void rowOverridesAppliedSparingly() {
        CollectionCreateDto dto = dailyDto();
        CollectionCreateDto.RowOverride zeroOverride = rowOverride("zero");
        zeroOverride.setHour(1);
        zeroOverride.setMinute(0);
        zeroOverride.setSameDayPriority("HIGH");
        CollectionCreateDto.RowOverride so2Override = rowOverride("SO2");
        so2Override.setHour(5);
        so2Override.setMinute(30);
        so2Override.setDurationOverrides(Collections.singletonMap("stableTimeSeconds", 300));
        dto.setRows(Arrays.asList(zeroOverride, so2Override));

        factory.createCollection(dto, "admin");

        List<PlanSaveDto> rows = savedRows(5);
        assertEquals(1, rows.get(0).getHour());
        assertEquals(0, rows.get(0).getMinute());
        assertEquals("HIGH", rows.get(0).getSameDayPriority());
        assertEquals(5, rows.get(4).getHour());
        assertEquals(30, rows.get(4).getMinute());
        assertEquals(Integer.valueOf(300), rows.get(4).getDurationOverrides().get("stableTimeSeconds"));
        // 稀疏覆盖：未覆盖的行保持模板默认（CO 仍 2:45 LOW、零点行无时长覆盖）
        assertEquals(2, rows.get(2).getHour());
        assertEquals("LOW", rows.get(2).getSameDayPriority());
        assertNull(rows.get(0).getDurationOverrides());
    }

    // ---------- 一次创建事务性、任一行失败零落库 ----------

    @Test
    void templateShapeViolationsRejectedBeforeAnySave() {
        CollectionCreateDto unknownGas = dailyDto();
        unknownGas.setInstruments(Collections.singletonList("PM10"));
        CollectionCreateDto duplicatedGas = dailyDto();
        duplicatedGas.setInstruments(Arrays.asList("SO2", "SO2"));
        CollectionCreateDto dailyNoInterval = dailyDto();
        dailyNoInterval.setIntervalDays(null);
        CollectionCreateDto weeklyNoDays = weeklyDto();
        weeklyNoDays.setWeekdays(null);
        CollectionCreateDto blankName = dailyDto();
        blankName.setCollectionName(" ");
        CollectionCreateDto longName = dailyDto();
        longName.setCollectionName(String.join("", Collections.nCopies(101, "站")));
        CollectionCreateDto badTemplate = dailyDto();
        badTemplate.setTemplate("MONTHLY");
        CollectionCreateDto templateNull = dailyDto();
        templateNull.setTemplate(null);
        CollectionCreateDto unknownRowKey = dailyDto();
        unknownRowKey.setRows(Collections.singletonList(rowOverride("NO")));
        CollectionCreateDto unselectedRowKey = dailyDto();
        unselectedRowKey.setInstruments(Collections.singletonList("SO2"));
        unselectedRowKey.setRows(Collections.singletonList(rowOverride("CO")));
        CollectionCreateDto duplicatedRowKey = dailyDto();
        duplicatedRowKey.setRows(Arrays.asList(rowOverride("SO2"), rowOverride("SO2")));

        CollectionCreateDto noInstruments = dailyDto();
        noInstruments.setInstruments(null);

        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[]{null, "请求体"});
        cases.add(new Object[]{templateNull, "template"});
        cases.add(new Object[]{badTemplate, "DAILY / WEEKLY"});
        cases.add(new Object[]{blankName, "collectionName"});
        cases.add(new Object[]{longName, "100"});
        cases.add(new Object[]{noInstruments, "气"});
        cases.add(new Object[]{dailyNoInterval, "intervalDays"});
        cases.add(new Object[]{weeklyNoDays, "weekdays"});
        cases.add(new Object[]{unknownGas, "气种"});
        cases.add(new Object[]{duplicatedGas, "重复"});
        cases.add(new Object[]{unknownRowKey, "rowKey"});
        cases.add(new Object[]{unselectedRowKey, "rowKey"});
        cases.add(new Object[]{duplicatedRowKey, "rowKey"});

        for (Object[] c : cases) {
            CollectionCreateDto bad = (CollectionCreateDto) c[0];
            String desc = (String) c[1];
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> factory.createCollection(bad, "admin"), "用例: " + desc);
            assertTrue(ex.getMessage().contains(desc), "消息应含「" + desc + "」实际: " + ex.getMessage());
        }
        verify(planService, never()).save(any(), anyString());
    }

    @Test
    void invalidRowBlocksAllInsertsWithFullErrorList() {
        // CO 跨度行分钟非法（第 3 行）：全量校验后一次抛，错误带行号与行名，零 insert
        CollectionCreateDto dto = dailyDto();
        CollectionCreateDto.RowOverride badMinute = rowOverride("CO");
        badMinute.setMinute(99);
        dto.setRows(Collections.singletonList(badMinute));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.createCollection(dto, "admin"));
        assertTrue(ex.getMessage().contains("第3行"), ex.getMessage());
        assertTrue(ex.getMessage().contains("CO-跨度"), ex.getMessage());
        verify(planService, never()).save(any(), anyString());
    }

    @Test
    void midInsertFailurePropagatesForTransactionalRollback() throws Exception {
        when(planService.save(any(PlanSaveDto.class), anyString())).thenAnswer(inv -> {
            if (idSeq.incrementAndGet() == 3) {
                throw new IllegalStateException("第三行落库失败");
            }
            QcmPlan saved = new QcmPlan();
            saved.setId((long) idSeq.get());
            return saved;
        });

        assertThrows(IllegalStateException.class, () -> factory.createCollection(dailyDto(), "admin"));
        verify(planService, times(3)).save(any(), anyString());

        // 批量原子：回滚由容器事务保证，单测层锁注解存在性（CGLIB 类代理先例：plain class + @Transactional）
        Method m = PlanCollectionFactory.class.getMethod("createCollection", CollectionCreateDto.class, String.class);
        assertTrue(m.isAnnotationPresent(Transactional.class), "createCollection 必须 @Transactional（批量原子）");
    }

    // ---------- 间隔预警边界与非阻断 force ----------

    @Test
    void warningBlocksWhenNoForce() {
        // 零点 0:45 预估 2700s：2700+2700+900=6300 = O3 槽 1:45，触及边界即覆盖 → 预警拦截零落库
        when(estimator.estimateTotalSeconds(argThat(r -> "multi_zero_check".equals(r.getQcType()))))
                .thenReturn(2700L);
        CollectionCreateResult result = factory.createCollection(dailyDto(), "admin");

        assertFalse(result.isCreated());
        assertTrue(result.getPlans().isEmpty());
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().get(0).contains("四气-零点"), result.getWarnings().get(0));
        assertTrue(result.getWarnings().get(0).contains("O3-跨度"), result.getWarnings().get(0));
        verify(planService, never()).save(any(), anyString());
    }

    @Test
    void justUnderBoundaryCreatesWithoutWarning() {
        // 2699s：2699+2700+900=6299 < 6300，差 1 秒不触预警（>= 才警）
        when(estimator.estimateTotalSeconds(argThat(r -> "multi_zero_check".equals(r.getQcType()))))
                .thenReturn(2699L);
        CollectionCreateResult result = factory.createCollection(dailyDto(), "admin");

        assertTrue(result.isCreated());
        assertTrue(result.getWarnings().isEmpty());
        savedRows(5);
    }

    @Test
    void forcePersistsDespiteWarning() {
        when(estimator.estimateTotalSeconds(argThat(r -> "multi_zero_check".equals(r.getQcType()))))
                .thenReturn(2700L);
        CollectionCreateDto dto = dailyDto();
        dto.setForce(Boolean.TRUE);

        CollectionCreateResult result = factory.createCollection(dto, "admin");

        assertTrue(result.isCreated());
        assertEquals(5, result.getPlans().size());
        assertEquals(1, result.getWarnings().size());
    }

    @Test
    void estimateFailureIsNoticeNotBlock() {
        // multi 行预估不可用（composer 接线前 getEnum 抛）：进提示清单，不拦截建计划
        when(estimator.estimateTotalSeconds(argThat(r -> "multi_zero_check".equals(r.getQcType()))))
                .thenThrow(new RuntimeException("EXECUTOR_TYPE_NOT_READY"));
        CollectionCreateResult result = factory.createCollection(dailyDto(), "admin");

        assertTrue(result.isCreated());
        assertEquals(5, result.getPlans().size());
        assertEquals(1, result.getEstimateFailures().size());
        assertTrue(result.getEstimateFailures().get(0).contains("四气-零点"), result.getEstimateFailures().get(0));
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void hugeEstimateOnLastRowNeverWarns() {
        // 末行（SO2 4:45 槽）预估再长也无下一槽可覆盖，不预警
        when(estimator.estimateTotalSeconds(argThat(r -> r.getPlanName().contains("SO2-跨度"))))
                .thenReturn(86400L);
        CollectionCreateResult result = factory.createCollection(dailyDto(), "admin");

        assertTrue(result.isCreated());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void hugeMidRowEstimateWarnsCoveringNextSlot() {
        // NOx 3:45 预估 7200s：13500+7200+900=21600 >= SO2 槽 4:45(17100) → 预警指向 SO2 行
        when(estimator.estimateTotalSeconds(argThat(r -> r.getPlanName().contains("NOx-跨度"))))
                .thenReturn(7200L);
        CollectionCreateResult result = factory.createCollection(dailyDto(), "admin");

        assertFalse(result.isCreated());
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().get(0).contains("NOx-跨度"), result.getWarnings().get(0));
        assertTrue(result.getWarnings().get(0).contains("SO2-跨度"), result.getWarnings().get(0));
    }

    @Test
    void overrideShiftedSlotChainCheckedInTimeOrder() {
        // 覆盖把 SO2 挪到 5:45 后，链序按时刻重排：O3(1:45)→CO(2:45)→NOx(3:45)→SO2(5:45)
        // CO 预估 7500s：9900+7500+900=18300 >= NOx 槽 3:45(13500) → 预警
        when(estimator.estimateTotalSeconds(argThat(r -> r.getPlanName().contains("CO-跨度"))))
                .thenReturn(7500L);
        CollectionCreateDto dto = dailyDto();
        CollectionCreateDto.RowOverride shift = rowOverride("SO2");
        shift.setHour(5);
        shift.setMinute(45);
        dto.setRows(Collections.singletonList(shift));

        CollectionCreateResult result = factory.createCollection(dto, "admin");

        assertFalse(result.isCreated());
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().get(0).contains("CO-跨度"), result.getWarnings().get(0));
        assertTrue(result.getWarnings().get(0).contains("NOx-跨度"), result.getWarnings().get(0));
    }

    @Test
    void callerRequired() {
        CollectionCreateDto dto = dailyDto();
        assertThrows(IllegalArgumentException.class, () -> factory.createCollection(dto, null));
        assertThrows(IllegalArgumentException.class, () -> factory.createCollection(dto, " "));
        verify(planService, never()).save(any(), anyString());
    }

    // ---------- 工厂只产独立行（逐行编辑/停用/删除走既有单行端点，无联动实体） ----------

    @Test
    void producedRowsAreIndependentPlanShapes() {
        factory.createCollection(dailyDto(), "admin");
        // 每行自带完整计划字段（id 空=走 save 创建路径），行间无共享对象引用可联动
        List<PlanSaveDto> rows = savedRows(5);
        for (PlanSaveDto row : rows) {
            assertNull(row.getId());
            assertNotNull(row.getPlanName());
            assertNotNull(row.getQcType());
            assertNotNull(row.getScheduleType());
        }
        // 零点行与跨度行 instruments 相互独立（改一行不影响另一行的对象）
        assertNotSame(rows.get(0).getInstruments(), rows.get(1).getInstruments());
    }

    // ---------- 统一调度展开：scheduleType 四值任一合法，template 只定默认优先级 ----------

    @Test
    void monthlyCollectionPassesMonthDaysAndWindowThrough() {
        // 日常集合（template=DAILY→LOW）配按月调度：快捷方式是预填不是限制
        CollectionCreateDto dto = dailyDto();
        dto.setScheduleType("MONTHLY");
        dto.setMonthDays(Arrays.asList(1, 15));
        dto.setPlanStartTime("2026-10-01T00:00:00Z");
        dto.setPlanEndTime("2027-09-30T00:00:00Z");

        CollectionCreateResult result = factory.createCollection(dto, "admin");

        assertTrue(result.isCreated());
        for (PlanSaveDto row : savedRows(5)) {
            assertEquals("MONTHLY", row.getScheduleType());
            assertEquals(Arrays.asList(1, 15), row.getMonthDays());
            assertEquals("2026-10-01T00:00:00Z", row.getPlanStartTime());
            assertEquals("2027-09-30T00:00:00Z", row.getPlanEndTime());
            assertEquals("LOW", row.getSameDayPriority());
        }
    }

    @Test
    void onceCollectionRejectedWithPhysicalReason() {
        // 集合=共享气路套件多行错峰，一次性单时刻会把全部行压到同一时刻同时通气，物理不可承载；
        // 文案须说清物理原因并指路替代方案。UI 已隐藏该选项，此拒绝是 API 面防线
        // （防直调绕过 UI 造成同时通气）；集合 DTO 已无 onceAt 字段——一次性输入面整体不存在
        CollectionCreateDto dto = dailyDto();
        dto.setScheduleType("ONCE");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.createCollection(dto, "admin"));
        assertTrue(ex.getMessage().contains("集合不支持一次性调度"), ex.getMessage());
        assertTrue(ex.getMessage().contains("逐行错峰"), ex.getMessage());
        assertTrue(ex.getMessage().contains("间隔 1 天"), ex.getMessage());
        verify(planService, never()).save(any(), anyString());
    }

    @Test
    void weeklyCollectionWithIntervalWeeksPassesThrough() {
        // 周核查集合配隔周：intervalWeeks 与锚点来源 planStartTime 逐行透传（>1 时行校验要求 planStartTime）
        CollectionCreateDto dto = weeklyDto();
        dto.setIntervalWeeks(2);
        dto.setPlanStartTime("2026-10-01T00:00:00Z");

        CollectionCreateResult result = factory.createCollection(dto, "admin");

        assertTrue(result.isCreated());
        for (PlanSaveDto row : savedRows(5)) {
            assertEquals("WEEKLY", row.getScheduleType());
            assertEquals(Integer.valueOf(2), row.getIntervalWeeks());
            assertEquals("2026-10-01T00:00:00Z", row.getPlanStartTime());
        }
    }

    @Test
    void templateDecoupledFromScheduleType() {
        // template 只定默认优先级与命名提示，与调度校验彻底解耦：
        // 日常模板（LOW）配按周调度、周模板（HIGH）配按天间隔调度都要能建
        CollectionCreateDto dailyWeekly = dailyDto();
        dailyWeekly.setScheduleType("WEEKLY");
        dailyWeekly.setWeekdays(Arrays.asList(2, 4));
        assertTrue(factory.createCollection(dailyWeekly, "admin").isCreated());

        CollectionCreateDto weeklyInterval = weeklyDto();
        weeklyInterval.setScheduleType("INTERVAL");
        weeklyInterval.setIntervalDays(3);
        weeklyInterval.setPlanStartTime("2026-10-01T00:00:00Z");
        assertTrue(factory.createCollection(weeklyInterval, "admin").isCreated());

        // mock 计数跨两次创建累计：前 5 行=按周集合，后 5 行=按天集合
        List<PlanSaveDto> rows = savedRows(10);
        for (PlanSaveDto row : rows.subList(0, 5)) {
            assertEquals("WEEKLY", row.getScheduleType());
            assertEquals("LOW", row.getSameDayPriority());
        }
        for (PlanSaveDto row : rows.subList(5, 10)) {
            assertEquals("INTERVAL", row.getScheduleType());
            assertEquals(Integer.valueOf(3), row.getIntervalDays());
            assertEquals("HIGH", row.getSameDayPriority());
        }
    }

    @Test
    void scheduleShapeViolationsRejectedBeforeAnySave() {
        // scheduleType 必填且四值闭集（集合不再产出 DAILY）；按 type 校验必填项与锚点依赖
        CollectionCreateDto noType = dailyDto();
        noType.setScheduleType(null);
        CollectionCreateDto dailyType = dailyDto();
        dailyType.setScheduleType("DAILY");
        CollectionCreateDto intervalNoStart = dailyDto();
        intervalNoStart.setPlanStartTime(null);
        CollectionCreateDto intervalNoDays = dailyDto();
        intervalNoDays.setIntervalDays(null);
        CollectionCreateDto weeklyOverOneNoStart = weeklyDto();
        weeklyOverOneNoStart.setIntervalWeeks(2);
        CollectionCreateDto monthlyNoDays = dailyDto();
        monthlyNoDays.setScheduleType("MONTHLY");
        CollectionCreateDto onceType = dailyDto();
        onceType.setScheduleType("ONCE");

        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[]{noType, "scheduleType"});
        cases.add(new Object[]{dailyType, "scheduleType"});
        cases.add(new Object[]{intervalNoStart, "planStartTime"});
        cases.add(new Object[]{intervalNoDays, "intervalDays"});
        cases.add(new Object[]{weeklyOverOneNoStart, "planStartTime"});
        cases.add(new Object[]{monthlyNoDays, "monthDays"});
        // ONCE 整体拒绝（物理不可承载，非缺字段问题）
        cases.add(new Object[]{onceType, "一次性调度"});

        for (Object[] c : cases) {
            CollectionCreateDto bad = (CollectionCreateDto) c[0];
            String desc = (String) c[1];
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> factory.createCollection(bad, "admin"), "用例: " + desc);
            assertTrue(ex.getMessage().contains(desc), "消息应含「" + desc + "」实际: " + ex.getMessage());
        }
        verify(planService, never()).save(any(), anyString());
    }
}
