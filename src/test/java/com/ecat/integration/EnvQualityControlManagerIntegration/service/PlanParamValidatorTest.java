package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanParamValidator 规则表逐条用例（FR-01-30 全表 + FR-01-27 矩阵）：
 * 每规则合法/非法各一；固定时钟保证 ONCE 时序判定确定性。
 *
 * @author coffee
 */
class PlanParamValidatorTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    private PlanParamValidator validator;
    private PlanSaveDto base;

    @BeforeEach
    void setUp() {
        validator = new PlanParamValidator(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));
        base = new PlanSaveDto();
        base.setPlanName("城区站零点计划");
        base.setQcType("zero_check");
        base.setInstruments(Collections.singletonList("SO2"));
        base.setScheduleType("DAILY");
        base.setHour(2);
        base.setMinute(0);
    }

    private List<String> validate(PlanSaveDto dto) {
        return validator.validate(dto);
    }

    private static void assertValid(PlanSaveDto dto, List<String> errors) {
        assertTrue(errors.isEmpty(), dto + " 应合法，实际错误: " + errors);
    }

    // ---------- 基线与名称 ----------

    @Test
    void baselineZeroCheckDailyIsValid() {
        assertValid(base, validate(base));
    }

    @Test
    void blankPlanNameRejected() {
        base.setPlanName(" ");
        assertTrue(validate(base).contains("计划名称不能为空"));
    }

    @Test
    void planNameOver100CharsRejected() {
        base.setPlanName(new String(new char[101]).replace('\0', '名'));
        assertTrue(validate(base).contains("计划名称长度不能超过 100 字符"));
    }

    @Test
    void planNameExactly100CharsValid() {
        base.setPlanName(new String(new char[100]).replace('\0', '名'));
        assertValid(base, validate(base));
    }

    // ---------- 调度基础 ----------

    @Test
    void unknownScheduleTypeRejected() {
        base.setScheduleType("HOURLY");
        assertTrue(validate(base).contains("调度类型必须是 DAILY/WEEKLY/MONTHLY/ONCE/INTERVAL 之一"));
    }

    @Test
    void hour24Rejected() {
        base.setHour(24);
        assertTrue(validate(base).contains("小时必须是 0-23 的整数"));
    }

    @Test
    void hourBoundary23Valid() {
        base.setHour(23);
        assertValid(base, validate(base));
    }

    @Test
    void minuteNegativeRejected() {
        base.setMinute(-1);
        assertTrue(validate(base).contains("分钟必须是 0-59 的整数"));
    }

    @Test
    void missingHourRejected() {
        base.setHour(null);
        assertTrue(validate(base).contains("小时必须是 0-23 的整数"));
    }

    // ---------- WEEKLY / MONTHLY ----------

    @Test
    void weeklyWithWeekdaysValid() {
        base.setScheduleType("WEEKLY");
        base.setWeekdays(Arrays.asList(1, 3));
        assertValid(base, validate(base));
    }

    @Test
    void weeklyEmptyWeekdaysRejected() {
        base.setScheduleType("WEEKLY");
        assertTrue(validate(base).contains("周几至少选择一项"));
    }

    @Test
    void weeklyWeekday8Rejected() {
        base.setScheduleType("WEEKLY");
        base.setWeekdays(Arrays.asList(1, 8));
        assertTrue(validate(base).contains("周几取值必须在 1-7 之间: 8"));
    }

    // ---------- 统一调度：INTERVAL 锚点依赖 planStartTime / WEEKLY intervalWeeks ----------

    @Test
    void intervalWithoutPlanStartTimeRejected() {
        // INTERVAL 锚点日=planStartTime 墙钟日（废除「保存当日」隐式锚），无 planStartTime 必须报错且文案含字段名
        base.setScheduleType("INTERVAL");
        base.setIntervalDays(2);
        List<String> errors = validate(base);
        assertTrue(errors.stream().anyMatch(e -> e.contains("planStartTime")),
                "应报含 planStartTime 的错误，实际: " + errors);
    }

    @Test
    void intervalWithPlanStartTimeValid() {
        base.setScheduleType("INTERVAL");
        base.setIntervalDays(2);
        base.setPlanStartTime("2026-09-24T00:00:00Z");
        assertValid(base, validate(base));
    }

    @Test
    void weeklyIntervalWeeksOmittedValid() {
        // 不传 intervalWeeks=每 1 周，与存量 WEEKLY 语义等价（API 兼容口径）
        base.setScheduleType("WEEKLY");
        base.setWeekdays(Arrays.asList(1, 3));
        assertValid(base, validate(base));
    }

    @Test
    void weeklyIntervalWeeksBoundariesValid() {
        base.setScheduleType("WEEKLY");
        base.setWeekdays(Arrays.asList(1, 3));
        base.setIntervalWeeks(1);
        assertValid(base, validate(base));
        // 上界 52 须配 planStartTime（>1 时周相位锚由其派生）
        base.setIntervalWeeks(52);
        base.setPlanStartTime("2026-09-24T00:00:00Z");
        assertValid(base, validate(base));
    }

    @Test
    void weeklyIntervalWeeksOutOfBoundsRejected() {
        base.setScheduleType("WEEKLY");
        base.setWeekdays(Arrays.asList(1, 3));
        base.setIntervalWeeks(0);
        assertTrue(validate(base).contains("隔周数必须是 1-52 的整数"));
        base.setIntervalWeeks(53);
        assertTrue(validate(base).contains("隔周数必须是 1-52 的整数"));
    }

    @Test
    void weeklyIntervalWeeksOverOneWithoutPlanStartRejected() {
        base.setScheduleType("WEEKLY");
        base.setWeekdays(Arrays.asList(1, 3));
        base.setIntervalWeeks(2);
        List<String> errors = validate(base);
        assertTrue(errors.stream().anyMatch(e -> e.contains("planStartTime")),
                "应报含 planStartTime 的错误，实际: " + errors);
        // 补上 planStartTime 即合法
        base.setPlanStartTime("2026-09-24T00:00:00Z");
        assertValid(base, validate(base));
    }

    @Test
    void monthlyWithMonthDaysValid() {
        base.setScheduleType("MONTHLY");
        base.setMonthDays(Arrays.asList(1, 15));
        assertValid(base, validate(base));
    }

    @Test
    void monthlyEmptyMonthDaysRejected() {
        base.setScheduleType("MONTHLY");
        assertTrue(validate(base).contains("几号至少选择一项"));
    }

    @Test
    void monthlyDay0Rejected() {
        base.setScheduleType("MONTHLY");
        base.setMonthDays(Arrays.asList(0, 15));
        assertTrue(validate(base).contains("几号取值必须在 1-31 之间: 0"));
    }

    // ---------- ONCE ----------

    @Test
    void onceImmediateValid() {
        base.setScheduleType("ONCE");
        base.setOnceMode("IMMEDIATE");
        assertValid(base, validate(base));
    }

    @Test
    void onceImmediateWithOnceAtRejected() {
        base.setScheduleType("ONCE");
        base.setOnceMode("IMMEDIATE");
        base.setOnceAt("2026-08-22T00:00:00Z");
        assertTrue(validate(base).contains("立刻执行模式不需要指定时刻（onceAt 须为空）"));
    }

    @Test
    void onceScheduledFutureValid() {
        base.setScheduleType("ONCE");
        base.setOnceMode("SCHEDULED");
        base.setOnceAt("2026-08-22T03:00:00Z");
        assertValid(base, validate(base));
    }

    @Test
    void onceScheduledRfc3339OffsetFormValid() {
        // RFC 3339 偏移形态（墙钟 11:00 东八 = 03:00Z 晚于 NOW）：与 Z 形态同为标准绝对时刻
        base.setScheduleType("ONCE");
        base.setOnceMode("SCHEDULED");
        base.setOnceAt("2026-08-22T11:00:00+08:00");
        assertValid(base, validate(base));
    }

    @Test
    void intervalPlanStartRfc3339OffsetFormValid() {
        base.setScheduleType("INTERVAL");
        base.setIntervalDays(2);
        base.setPlanStartTime("2026-09-23T16:34:00+08:00");
        assertValid(base, validate(base));
    }

    @Test
    void onceScheduledMissingOnceAtRejected() {
        base.setScheduleType("ONCE");
        base.setOnceMode("SCHEDULED");
        assertTrue(validate(base).contains("指定时刻模式必须填写触发时刻（onceAt）"));
    }

    @Test
    void onceScheduledPastOnceAtRejected() {
        base.setScheduleType("ONCE");
        base.setOnceMode("SCHEDULED");
        base.setOnceAt("2026-08-20T00:00:00Z");
        assertTrue(validate(base).stream().anyMatch(m -> m.contains("指定时刻必须晚于当前时刻")), "实际错误: " + validate(base));
    }

    @Test
    void onceScheduledOnceAtEqualToNowRejected() {
        base.setScheduleType("ONCE");
        base.setOnceMode("SCHEDULED");
        base.setOnceAt(NOW.toString());
        assertTrue(validate(base).stream().anyMatch(m -> m.contains("指定时刻必须晚于当前时刻")), "实际错误: " + validate(base));
    }

    @Test
    void onceBadModeRejected() {
        base.setScheduleType("ONCE");
        base.setOnceMode("sometime");
        assertTrue(validate(base).contains("一次性模式必须是 IMMEDIATE/SCHEDULED 之一"));
    }

    @Test
    void onceBadOnceAtFormatRejected() {
        base.setScheduleType("ONCE");
        base.setOnceMode("SCHEDULED");
        base.setOnceAt("2026-08-22 03:00");
        assertTrue(validate(base).stream().anyMatch(m -> m.contains("必须是 RFC 3339 带时区偏移的时刻")));
    }

    // ---------- 质控类型与仪器矩阵 ----------

    @Test
    void unknownQcTypeRejected() {
        base.setQcType("some_future_type");
        assertTrue(validate(base).contains("不支持的质控类型: some_future_type"));
    }

    @Test
    void allSevenEnumTypesAccepted() {
        for (String type : Arrays.asList("zero_check", "span_check", "multi_check", "precision_check",
                "accuracy_check", "conversion_check", "audit_span_check")) {
            base.setQcType(type);
            base.setInstruments(Collections.singletonList("NO2"));
            base.setConcentrationPpb(type.equals("span_check") || type.equals("audit_span_check")
                    ? BigDecimal.valueOf(400) : null);
            assertTrue(validate(base).isEmpty(), type + " 应合法: " + validate(base));
        }
    }

    @Test
    void missingInstrumentRejected() {
        base.setInstruments(null);
        assertTrue(validate(base).contains("必须选择 1 台仪器"));
    }

    @Test
    void twoInstrumentsOnSingleInstrumentTypeRejected() {
        base.setInstruments(Arrays.asList("SO2", "CO"));
        assertTrue(validate(base).contains("该质控类型只能选择 1 台仪器，实际 2 台"));
    }

    @Test
    void instrumentOutsideCandidatesRejected() {
        base.setInstruments(Collections.singletonList("PM10"));
        assertTrue(validate(base).contains("仪器必须是 SO2/NO2/CO/O3 之一: PM10"));
    }

    @Test
    void conversionCheckLockedToNo2() {
        base.setQcType("conversion_check");
        base.setInstruments(Collections.singletonList("CO"));
        assertTrue(validate(base).contains("转换效率检查锁定仪器 NOx（NO2），不可更改"));
    }

    @Test
    void conversionCheckWithNo2Valid() {
        base.setQcType("conversion_check");
        base.setInstruments(Collections.singletonList("NO2"));
        assertValid(base, validate(base));
    }

    // ---------- 浓度 / 百分比 / 流量 ----------

    @Test
    void spanCheckWithoutConcentrationRejected() {
        base.setQcType("span_check");
        assertTrue(validate(base).contains("该质控类型必须填写标气浓度（ppb，>0）"));
    }

    @Test
    void spanCheckZeroConcentrationRejected() {
        base.setQcType("span_check");
        base.setConcentrationPpb(BigDecimal.ZERO);
        assertTrue(validate(base).contains("标气浓度必须大于 0"));
    }

    @Test
    void spanCheckPositiveConcentrationValid() {
        base.setQcType("span_check");
        base.setConcentrationPpb(BigDecimal.valueOf(400));
        base.setFlowRateLpm(BigDecimal.valueOf(4.0));
        assertValid(base, validate(base));
    }

    @Test
    void pointPercentsAscendingPairValid() {
        base.setQcType("multi_check");
        base.setPointPercents(Arrays.asList(0.1f, 0.2f, 0.8f));
        assertValid(base, validate(base));
    }

    @Test
    void pointPercentsSingleItemRejected() {
        base.setQcType("multi_check");
        base.setPointPercents(Collections.singletonList(0.5f));
        assertTrue(validate(base).contains("量程百分比序列至少 2 项"));
    }

    @Test
    void pointPercentsNotAscendingRejected() {
        base.setQcType("multi_check");
        base.setPointPercents(Arrays.asList(0.2f, 0.1f));
        assertTrue(validate(base).contains("量程百分比序列必须严格升序"));
    }

    @Test
    void pointPercentsOutOfRangeRejected() {
        base.setQcType("accuracy_check");
        base.setPointPercents(Arrays.asList(0.1f, 1.5f));
        assertTrue(validate(base).stream().anyMatch(m -> m.contains("每项必须在 0~1 之间")));
    }

    @Test
    void pointPercentsOnZeroCheckRejected() {
        base.setPointPercents(Arrays.asList(0.1f, 0.2f));
        assertTrue(validate(base).contains("该质控类型不支持量程百分比序列"));
    }

    @Test
    void flowRateUpperBound50Valid() {
        base.setQcType("span_check");
        base.setConcentrationPpb(BigDecimal.valueOf(400));
        base.setFlowRateLpm(BigDecimal.valueOf(50));
        assertValid(base, validate(base));
    }

    @Test
    void flowRateAbove50Rejected() {
        base.setQcType("span_check");
        base.setConcentrationPpb(BigDecimal.valueOf(400));
        base.setFlowRateLpm(BigDecimal.valueOf(50.1));
        assertTrue(validate(base).contains("标气流量必须在 (0, 50] L/min 之间"));
    }

    @Test
    void flowRateZeroRejected() {
        base.setQcType("span_check");
        base.setConcentrationPpb(BigDecimal.valueOf(400));
        base.setFlowRateLpm(BigDecimal.ZERO);
        assertTrue(validate(base).contains("标气流量必须在 (0, 50] L/min 之间"));
    }

    @Test
    void flowRateOnZeroCheckValidWhenInRange() {
        // G-REQ-9：流量放开为全类型一致（可空，提供则 ∈(0,50]），零点类不再拒绝
        base.setFlowRateLpm(BigDecimal.valueOf(4));
        assertValid(base, validate(base));
    }

    @Test
    void flowRateZeroOnZeroCheckRejected() {
        base.setFlowRateLpm(BigDecimal.ZERO);
        assertTrue(validate(base).contains("标气流量必须在 (0, 50] L/min 之间"));
    }

    // ---------- 时长覆盖与有效期 ----------

    @Test
    void durationOverrideWhitelistedPositiveIntValid() {
        base.setDurationOverrides(Collections.singletonMap("stableTimeSeconds", 600));
        assertValid(base, validate(base));
    }

    @Test
    void durationOverrideUnknownKeyRejected() {
        base.setDurationOverrides(Collections.singletonMap("magicSeconds", 10));
        assertTrue(validate(base).contains("时长参数不在白名单内: magicSeconds"));
    }

    @Test
    void durationOverrideZeroValueRejected() {
        base.setDurationOverrides(Collections.singletonMap("sampleCount", 0));
        assertTrue(validate(base).contains("时长参数 sampleCount 必须是正整数（秒）"));
    }

    @Test
    void durationOverridePercentListKeyAscendingValid() {
        base.setQcType("multi_check");
        base.setDurationOverrides(Collections.singletonMap("multiPointPercents",
                Arrays.asList(0.0, 0.2, 0.8)));
        assertValid(base, validate(base));
    }

    @Test
    void durationOverridePercentListKeyBadListRejected() {
        base.setQcType("multi_check");
        base.setDurationOverrides(Collections.singletonMap("multiPointPercents",
                Arrays.asList(0.5, 0.5)));
        assertTrue(validate(base).contains("multiPointPercents 必须是 0~1 严格升序、至少 2 项的序列"));
    }

    @Test
    void windowStartBeforeEndValid() {
        base.setPlanStartTime("2026-08-22T00:00:00Z");
        base.setPlanEndTime("2026-12-31T00:00:00Z");
        assertValid(base, validate(base));
    }

    @Test
    void windowStartAfterEndRejected() {
        base.setPlanStartTime("2027-01-01T00:00:00Z");
        base.setPlanEndTime("2026-12-31T00:00:00Z");
        assertTrue(validate(base).contains("有效期起必须早于有效期止"));
    }

    @Test
    void windowEqualStartEndRejected() {
        base.setPlanStartTime("2026-12-31T00:00:00Z");
        base.setPlanEndTime("2026-12-31T00:00:00Z");
        assertTrue(validate(base).contains("有效期起必须早于有效期止"));
    }

    @Test
    void windowBadFormatRejected() {
        base.setPlanStartTime("2026-08-22");
        assertTrue(validate(base).stream().anyMatch(m -> m.contains("有效期起必须是 RFC 3339 带时区偏移的时刻")));
    }

    @Test
    void nullDtoRejected() {
        List<String> errors = validator.validate(null);
        assertFalse(errors.isEmpty());
    }

    // ---------- INTERVAL 调度（intervalDays∈[1,31]；锚点日由 planStartTime 派生，必填） ----------

    @Test
    void intervalWithTemplateDefaultDaysValid() {
        base.setScheduleType("INTERVAL");
        base.setIntervalDays(2);
        base.setPlanStartTime("2026-09-24T00:00:00Z");
        assertValid(base, validate(base));
    }

    @Test
    void intervalBoundaryDays1And31Valid() {
        base.setScheduleType("INTERVAL");
        base.setPlanStartTime("2026-09-24T00:00:00Z");
        base.setIntervalDays(1);
        assertValid(base, validate(base));
        base.setIntervalDays(31);
        assertValid(base, validate(base));
    }

    @Test
    void intervalDaysMissingRejected() {
        base.setScheduleType("INTERVAL");
        assertTrue(validate(base).contains("间隔天数必须是 1-31 的整数"));
    }

    @Test
    void intervalDaysOutOfRangeRejected() {
        base.setScheduleType("INTERVAL");
        base.setIntervalDays(0);
        assertTrue(validate(base).contains("间隔天数必须是 1-31 的整数"));
        base.setIntervalDays(32);
        assertTrue(validate(base).contains("间隔天数必须是 1-31 的整数"));
    }

    @Test
    void intervalHourMinuteStillDomainChecked() {
        base.setScheduleType("INTERVAL");
        base.setIntervalDays(2);
        base.setHour(24);
        assertTrue(validate(base).contains("小时必须是 0-23 的整数"));
    }

    // ---------- 校准策略（03 设计 §3/§7.4：仅零点/跨度/多仪器零点可设，缺省=STANDARD 落 NULL） ----------

    @Test
    void calibrationPolicyAbsentValidOnZeroCheck() {
        assertValid(base, validate(base));
    }

    @Test
    void calibrationPolicyBothValuesValidOnApplicableTypes() {
        base.setCalibrationPolicy("STANDARD");
        assertValid(base, validate(base));
        base.setCalibrationPolicy("CALIBRATE_LOW_DRIFT");
        assertValid(base, validate(base));
        PlanSaveDto multiZero = multiZeroBase("SO2");
        multiZero.setCalibrationPolicy("CALIBRATE_LOW_DRIFT");
        assertValid(multiZero, validate(multiZero));
    }

    @Test
    void calibrationPolicyBogusValueRejected() {
        base.setCalibrationPolicy("ALWAYS_CALIBRATE");
        assertTrue(validate(base).contains("校准策略必须是 STANDARD/CALIBRATE_LOW_DRIFT 之一: ALWAYS_CALIBRATE"));
    }

    @Test
    void calibrationPolicyOnInapplicableTypeRejected() {
        base.setQcType("multi_check");
        base.setCalibrationPolicy("STANDARD");
        assertTrue(validate(base).contains("该质控类型不支持校准策略（仅零点/跨度/多仪器零点可设）"));
    }

    // ---------- 同日优先级（03 设计 §7.4：计划通用字段，缺省=NONE 落 NULL） ----------

    @Test
    void sameDayPriorityTriStatesValid() {
        base.setSameDayPriority("NONE");
        assertValid(base, validate(base));
        base.setSameDayPriority("LOW");
        assertValid(base, validate(base));
        base.setSameDayPriority("HIGH");
        assertValid(base, validate(base));
    }

    @Test
    void sameDayPriorityBogusRejected() {
        base.setSameDayPriority("MIDDLE");
        assertTrue(validate(base).contains("同日优先级必须是 NONE/LOW/HIGH 之一: MIDDLE"));
    }

    // ---------- multi_zero_check 多仪器矩阵（FR-01-27/28，Phase 4） ----------

    private PlanSaveDto multiZeroBase(String... instruments) {
        PlanSaveDto dto = new PlanSaveDto();
        dto.setPlanName("多仪器零点计划");
        dto.setQcType("multi_zero_check");
        dto.setInstruments(Arrays.asList(instruments));
        dto.setScheduleType("DAILY");
        dto.setHour(0);
        dto.setMinute(0);
        return dto;
    }

    @Test
    void multiZeroCheckSingleInstrumentValid() {
        assertValid(multiZeroBase("SO2"), validate(multiZeroBase("SO2")));
    }

    @Test
    void multiZeroCheckEmptyInstrumentsRejected() {
        // ≥1 下界锁定（03 设计 §7.4：多仪器列表 ≥1，空列表拒绝而非静默放行）
        PlanSaveDto dto = multiZeroBase();
        assertTrue(validate(dto).contains("至少选择 1 台仪器"));
    }

    @Test
    void multiZeroCheckTwoInstrumentsValid() {
        PlanSaveDto dto = multiZeroBase("SO2", "NO2");
        assertValid(dto, validate(dto));
    }

    @Test
    void multiZeroCheckFourInstrumentsValid() {
        PlanSaveDto dto = multiZeroBase("SO2", "NO2", "CO", "O3");
        assertValid(dto, validate(dto));
    }

    @Test
    void multiZeroCheckFiveInstrumentsRejected() {
        PlanSaveDto dto = multiZeroBase("SO2", "NO2", "CO", "O3", "SO2");
        assertTrue(validate(dto).stream().anyMatch(m -> m.contains("多仪器类型至多选择")));
    }

    @Test
    void multiZeroCheckWithConcentrationRejected() {
        PlanSaveDto dto = multiZeroBase("SO2");
        dto.setConcentrationPpb(BigDecimal.valueOf(400));
        assertTrue(validate(dto).contains("多仪器零点质控无需标气浓度（concentrationPpb 须为空）"));
    }

    @Test
    void multiZeroCheckWithFlowRateValid() {
        PlanSaveDto dto = multiZeroBase("SO2");
        dto.setFlowRateLpm(BigDecimal.valueOf(4));
        assertValid(dto, validate(dto));
    }

    @Test
    void multiZeroCheckWithPointPercentsRejected() {
        PlanSaveDto dto = multiZeroBase("SO2");
        dto.setPointPercents(Arrays.asList(0.1f, 0.2f));
        assertTrue(validate(dto).contains("该质控类型不支持量程百分比序列"));
    }

    @Test
    void multiZeroCheckZeroClassDurationKeysValid() {
        PlanSaveDto dto = multiZeroBase("SO2", "CO");
        dto.setDurationOverrides(new java.util.HashMap<String, Object>() {{
            put("commandDelaySeconds", 2);
            put("stableTimeSeconds", 1280);
            put("sampleCount", 3);
            put("sampleIntervalSeconds", 30);
            put("calibrationTimeSeconds", 61);
            put("verificationStableTimeSeconds", 30);
            put("verificationSampleCount", 3);
            put("zeroGasOpenDelaySeconds", 3);
            put("recoveryDelaySeconds", 60);
        }});
        assertValid(dto, validate(dto));
    }

    @Test
    void multiZeroCheckNonZeroDurationKeyRejected() {
        PlanSaveDto dto = multiZeroBase("SO2");
        dto.setDurationOverrides(new java.util.HashMap<String, Object>() {{
            put("precisionRounds", 6);
        }});
        assertTrue(validate(dto).contains("多仪器零点质控仅支持零点类时长参数: precisionRounds"));
    }

    @Test
    void multiZeroCheck_seedRow1_semanticsPassValidatorAndAssembler() {
        // seed #1「零点质控-全部仪器」（qcm_data.sql：multi_zero_check/4 台/DAILY 00:00/全默认参数）
        PlanSaveDto dto = multiZeroBase("SO2", "NO2", "CO", "O3");
        dto.setPlanName("零点质控-全部仪器");
        assertValid(dto, validate(dto));
        // 装配路径不抛：plan 行语义 → QcExecutionRequest（SCHEDULED/MANUAL 两源共用）
        com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan plan =
                new com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan();
        plan.setId(1L);
        plan.setPlanName("零点质控-全部仪器");
        plan.setQcType("multi_zero_check");
        plan.setInstruments("[\"SO2\",\"NO2\",\"CO\",\"O3\"]");
        plan.setScheduleType("DAILY");
        plan.setScheduleConfig("{\"hour\":0,\"minute\":0}");
        plan.setCalibrationPolicy("CALIBRATE_LOW_DRIFT");
        com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest assembled =
                PlanRequestAssembler.assemble(plan);
        assertEquals("multi_zero_check", assembled.getQcType());
        assertEquals(4, assembled.getInstruments().size());
        // 校准策略列随装配透传（multi 分支落 flowParams.calibrationPolicy，NULL=STANDARD）
        assertEquals("CALIBRATE_LOW_DRIFT", assembled.getCalibrationPolicy());
    }
}
