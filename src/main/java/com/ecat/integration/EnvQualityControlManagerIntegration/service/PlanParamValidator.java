package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.Utils.DateTimeUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划参数校验器（FR-01-30 全表 + FR-01-27 类型矩阵 + 03 设计 §7.4 增列矩阵
 * 「INTERVAL 调度 / 校准策略 / 同日优先级」）：REST / SDK / 页面三源共用的
 * 单一事实源（FR-03-17）——service 保存前必经，非法逐字段收集为错误清单返回，
 * 由 controller 转逐条展示（不静默修正、不猜默认）。
 *
 * <p>时钟注入：ONCE 指定时刻须严格晚于当前时刻（FR-01-13），固定 Clock 保证测试确定性。</p>
 *
 * <p>多仪器扩展位：{@link #MULTI_INSTRUMENT_TYPES} 白名单当前含 multi_zero_check
 * （1~4 台多选），未知质控类型默认拒绝（枚举反查失败即报「不支持的质控类型」，
 * 新枚举加入后自动放行）。</p>
 *
 * @author coffee
 */
@Service
public class PlanParamValidator {

    /** 允许多仪器的质控类型（FR-01-27 矩阵）：当前仅 multi_zero_check（1~4 台，允许 1 台）。 */
    static final Set<String> MULTI_INSTRUMENT_TYPES = new HashSet<>(
            Collections.singletonList(QualityControlTypeEnum.MULTI_ZERO_CHECK.getName()));

    /** multi_zero_check 的 durationOverrides 子集（零点类参数，FR-01-27 矩阵：无浓度/无流量/无百分比）。 */
    private static final Set<String> MULTI_ZERO_DURATION_KEYS = new HashSet<>(Arrays.asList(
            "commandDelaySeconds", "stableTimeSeconds", "sampleCount", "sampleIntervalSeconds",
            "calibrationTimeSeconds", "verificationStableTimeSeconds", "verificationSampleCount",
            "zeroGasOpenDelaySeconds", "recoveryDelaySeconds"));

    /** 仪器候选闭集（FR-01-28；代码键 NO2 ↔ 展示名 NOx）。 */
    private static final Set<String> INSTRUMENT_CANDIDATES = new HashSet<>(Arrays.asList("SO2", "NO2", "CO", "O3"));

    /** 需要绝对浓度（必填 >0 ppb）的类型。 */
    private static final Set<String> CONCENTRATION_REQUIRED_TYPES = new HashSet<>(Arrays.asList(
            QualityControlTypeEnum.SPAN_CHECK.getName(), QualityControlTypeEnum.AUDIT_SPAN_CHECK.getName()));

    /** 支持量程百分比序列的类型。 */
    private static final Set<String> POINT_PERCENT_TYPES = new HashSet<>(Arrays.asList(
            QualityControlTypeEnum.MULTI_CHECK.getName(), QualityControlTypeEnum.ACCURACY_CHECK.getName()));

    /** durationOverrides 白名单（01 §6 FR-01-33 参数表 14 键，与 composer flowParams key 同词汇）。 */
    private static final Set<String> DURATION_OVERRIDE_KEYS = new HashSet<>(Arrays.asList(
            "commandDelaySeconds", "stableTimeSeconds", "sampleCount", "sampleIntervalSeconds",
            "calibrationTimeSeconds", "verificationStableTimeSeconds", "verificationSampleCount",
            "zeroGasOpenDelaySeconds", "recoveryDelaySeconds",
            "precisionRounds", "conversionRounds", "gptTimeSeconds",
            "multiPointPercents", "accuracyPointPercents"));

    private static final List<String> ONCE_MODES = Arrays.asList("IMMEDIATE", "SCHEDULED");
    private static final List<String> SCHEDULE_TYPES = Arrays.asList("DAILY", "WEEKLY", "MONTHLY", "ONCE", "INTERVAL");

    /** 校准策略值域（03 设计 §3）：与 composer 侧 CalibrationPolicy 契约同词汇；跨仓并行，本仓按字符串闭集校验不引 composer 枚举。 */
    private static final Set<String> CALIBRATION_POLICIES = new HashSet<>(Arrays.asList(
            "STANDARD", "CALIBRATE_LOW_DRIFT"));

    /** 可设校准策略的质控类型（03 设计 §3）：零点/跨度/多仪器零点；人工核查与其余类型不适用。 */
    private static final Set<String> POLICY_APPLICABLE_TYPES = new HashSet<>(Arrays.asList(
            QualityControlTypeEnum.ZERO_CHECK.getName(), QualityControlTypeEnum.SPAN_CHECK.getName(),
            QualityControlTypeEnum.MULTI_ZERO_CHECK.getName()));

    /** 同日优先级值域（03 设计 §7.4）：行级三态，计划通用字段不限类型。 */
    private static final Set<String> SAME_DAY_PRIORITIES = new HashSet<>(Arrays.asList("NONE", "LOW", "HIGH"));

    /** 星期展示名（1=周一..7=周日，摘要文案与校验共用下标语义）。 */
    public static final List<String> WEEKDAY_NAMES = Arrays.asList("周一", "周二", "周三", "周四", "周五", "周六", "周日");

    private final Clock clock;

    @Autowired
    public PlanParamValidator() {
        this(Clock.systemDefaultZone());
    }

    /** 测试构造：注入固定时钟。 */
    public PlanParamValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * 逐字段校验，返回错误清单（空 = 合法）。
     */
    public List<String> validate(PlanSaveDto dto) {
        List<String> errors = new ArrayList<>();
        if (dto == null) {
            errors.add("请求体不能为空");
            return errors;
        }
        validateBasics(dto, errors);
        QualityControlTypeEnum qcEnum = validateQcType(dto, errors);
        validateInstruments(dto, qcEnum, errors);
        validateConcentration(dto, qcEnum, errors);
        validatePointPercents(dto, qcEnum, errors);
        validateFlowRate(dto, qcEnum, errors);
        validateDurationOverrides(dto, qcEnum, errors);
        validateCalibrationPolicy(dto, qcEnum, errors);
        validateSameDayPriority(dto, errors);
        validateWindow(dto, errors);
        return errors;
    }

    /**
     * 执行参数子域校验（SDK 直接触发，FR-03-17）：只跑与「一次执行」相关的六个字段组
     * （类型/仪器矩阵/浓度/量程百分比/流量/时长覆盖），计划保存域字段（名称/调度/有效期）不参与——
     * 与 {@link #validate(PlanSaveDto)} 共享同一组私有规则，保证 REST 与 SDK 拒绝口径一致。
     */
    public List<String> validateExecutionParams(PlanSaveDto dto) {
        List<String> errors = new ArrayList<>();
        if (dto == null) {
            errors.add("请求体不能为空");
            return errors;
        }
        QualityControlTypeEnum qcEnum = validateQcType(dto, errors);
        validateInstruments(dto, qcEnum, errors);
        validateConcentration(dto, qcEnum, errors);
        validatePointPercents(dto, qcEnum, errors);
        validateFlowRate(dto, qcEnum, errors);
        validateDurationOverrides(dto, qcEnum, errors);
        return errors;
    }

    private void validateBasics(PlanSaveDto dto, List<String> errors) {
        if (dto.getPlanName() == null || dto.getPlanName().trim().isEmpty()) {
            errors.add("计划名称不能为空");
        } else if (dto.getPlanName().length() > 100) {
            errors.add("计划名称长度不能超过 100 字符");
        }
        if (dto.getScheduleType() == null || !SCHEDULE_TYPES.contains(dto.getScheduleType())) {
            errors.add("调度类型必须是 DAILY/WEEKLY/MONTHLY/ONCE/INTERVAL 之一");
            return;
        }
        Integer hour = dto.getHour();
        if (hour == null || hour < 0 || hour > 23) {
            errors.add("小时必须是 0-23 的整数");
        }
        Integer minute = dto.getMinute();
        if (minute == null || minute < 0 || minute > 59) {
            errors.add("分钟必须是 0-59 的整数");
        }
        if ("WEEKLY".equals(dto.getScheduleType())) {
            validateDaySet(dto.getWeekdays(), 1, 7, "周几", errors);
            // 隔周数可空=每 1 周（与存量无键语义等价）；>1 时周相位锚由 planStartTime 派生，须提供
            Integer intervalWeeks = dto.getIntervalWeeks();
            if (intervalWeeks != null && (intervalWeeks < 1 || intervalWeeks > 52)) {
                errors.add("隔周数必须是 1-52 的整数");
            }
            if (intervalWeeks != null && intervalWeeks > 1 && isBlank(dto.getPlanStartTime())) {
                errors.add("隔周数大于 1 时必须提供有效期起 planStartTime（周相位锚点由其派生）");
            }
        }
        if ("MONTHLY".equals(dto.getScheduleType())) {
            validateDaySet(dto.getMonthDays(), 1, 31, "几号", errors);
        }
        if ("ONCE".equals(dto.getScheduleType())) {
            validateOnce(dto, errors);
        }
        if ("INTERVAL".equals(dto.getScheduleType())) {
            // 间隔天数必填（模板默认 2 由前端预填）；锚点日 anchorDate 由服务端从
            // planStartTime 墙钟日派生写入 config——无 planStartTime 无从派生，必须报错
            Integer intervalDays = dto.getIntervalDays();
            if (intervalDays == null || intervalDays < 1 || intervalDays > 31) {
                errors.add("间隔天数必须是 1-31 的整数");
            }
            if (isBlank(dto.getPlanStartTime())) {
                errors.add("按天间隔调度必须提供有效期起 planStartTime（间隔锚点日由其派生）");
            }
        }
    }

    /** 空串语义统一：null 或纯空白都视为未填（与 blankToNull 落库口径一致）。 */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 非空子集校验（WEEKLY weekdays 1-7 / MONTHLY monthDays 1-31）。 */
    private void validateDaySet(List<Integer> days, int min, int max, String label, List<String> errors) {
        if (days == null || days.isEmpty()) {
            errors.add(label + "至少选择一项");
            return;
        }
        for (Integer d : days) {
            if (d == null || d < min || d > max) {
                errors.add(label + "取值必须在 " + min + "-" + max + " 之间: " + d);
            }
        }
    }

    /** ONCE：模式二选一；SCHEDULED 须 onceAt 严格晚于当前（FR-01-13）；IMMEDIATE onceAt 须空。 */
    private void validateOnce(PlanSaveDto dto, List<String> errors) {
        String mode = dto.getOnceMode();
        if (mode == null || !ONCE_MODES.contains(mode)) {
            errors.add("一次性模式必须是 IMMEDIATE/SCHEDULED 之一");
            return;
        }
        if ("IMMEDIATE".equals(mode)) {
            if (dto.getOnceAt() != null && !dto.getOnceAt().trim().isEmpty()) {
                errors.add("立刻执行模式不需要指定时刻（onceAt 须为空）");
            }
            return;
        }
        String onceAt = dto.getOnceAt();
        if (onceAt == null || onceAt.trim().isEmpty()) {
            errors.add("指定时刻模式必须填写触发时刻（onceAt）");
            return;
        }
        Instant at = parseInstant(onceAt, "指定时刻", errors);
        if (at != null && !at.isAfter(clock.instant())) {
            errors.add("指定时刻必须晚于当前时刻（FR-01-13，不静默改为立刻执行）");
        }
    }

    /** 质控类型：枚举反查（未知默认拒绝，Phase 4 新枚举自动放行）。返回 null 表示未知。 */
    private QualityControlTypeEnum validateQcType(PlanSaveDto dto, List<String> errors) {
        String qcType = dto.getQcType();
        for (QualityControlTypeEnum e : QualityControlTypeEnum.values()) {
            if (e.getName().equals(qcType)) {
                return e;
            }
        }
        errors.add("不支持的质控类型: " + qcType);
        return null;
    }

    /** 仪器矩阵（FR-01-27）：多仪器类型白名单可 >1 台；其余恰 1 台且 ∈ 候选闭集；conversion 锁 NO2。 */
    private void validateInstruments(PlanSaveDto dto, QualityControlTypeEnum qcEnum, List<String> errors) {
        if (qcEnum == null) {
            return;
        }
        List<String> instruments = dto.getInstruments();
        boolean multiAllowed = MULTI_INSTRUMENT_TYPES.contains(qcEnum.getName());
        if (instruments == null || instruments.isEmpty()) {
            errors.add(multiAllowed ? "至少选择 1 台仪器" : "必须选择 1 台仪器");
            return;
        }
        if (!multiAllowed && instruments.size() != 1) {
            errors.add("该质控类型只能选择 1 台仪器，实际 " + instruments.size() + " 台");
            return;
        }
        if (multiAllowed && instruments.size() > INSTRUMENT_CANDIDATES.size()) {
            errors.add("多仪器类型至多选择 " + INSTRUMENT_CANDIDATES.size() + " 台仪器");
            return;
        }
        for (String instrument : instruments) {
            if (!INSTRUMENT_CANDIDATES.contains(instrument)) {
                errors.add("仪器必须是 SO2/NO2/CO/O3 之一: " + instrument);
            }
        }
        if (qcEnum == QualityControlTypeEnum.CONVERSION_CHECK
                && (instruments.size() != 1 || !"NO2".equals(instruments.get(0)))) {
            errors.add("转换效率检查锁定仪器 NOx（NO2），不可更改");
        }
    }

    /** 浓度：span/audit 必填 >0；multi_zero_check 必须空（零点任务无标气浓度语义）；其余提供时须 >0。 */
    private void validateConcentration(PlanSaveDto dto, QualityControlTypeEnum qcEnum, List<String> errors) {
        if (qcEnum == null) {
            return;
        }
        BigDecimal conc = dto.getConcentrationPpb();
        if (conc != null && qcEnum == QualityControlTypeEnum.MULTI_ZERO_CHECK) {
            errors.add("多仪器零点质控无需标气浓度（concentrationPpb 须为空）");
            return;
        }
        boolean required = CONCENTRATION_REQUIRED_TYPES.contains(qcEnum.getName());
        if (conc == null) {
            if (required) {
                errors.add("该质控类型必须填写标气浓度（ppb，>0）");
            }
            return;
        }
        if (conc.signum() <= 0) {
            errors.add("标气浓度必须大于 0");
        }
    }

    /** 量程百分比序列：仅 multi/accuracy 可用；提供时升序 ≥2 项且每项 [0,1]。 */
    private void validatePointPercents(PlanSaveDto dto, QualityControlTypeEnum qcEnum, List<String> errors) {
        if (qcEnum == null) {
            return;
        }
        List<Float> percents = dto.getPointPercents();
        if (percents == null) {
            return;
        }
        if (!POINT_PERCENT_TYPES.contains(qcEnum.getName())) {
            errors.add("该质控类型不支持量程百分比序列");
            return;
        }
        if (percents.size() < 2) {
            errors.add("量程百分比序列至少 2 项");
            return;
        }
        for (Float p : percents) {
            if (p == null || p < 0f || p > 1f) {
                errors.add("量程百分比每项必须在 0~1 之间: " + p);
                return;
            }
        }
        for (int i = 1; i < percents.size(); i++) {
            if (percents.get(i) <= percents.get(i - 1)) {
                errors.add("量程百分比序列必须严格升序");
                return;
            }
        }
    }

    /** 流量：全类型一致（G-REQ-9）——可空，提供则 ∈(0,50]。 */
    private void validateFlowRate(PlanSaveDto dto, QualityControlTypeEnum qcEnum, List<String> errors) {
        if (qcEnum == null) {
            return;
        }
        BigDecimal flow = dto.getFlowRateLpm();
        if (flow == null) {
            return;
        }
        if (flow.signum() <= 0 || flow.compareTo(BigDecimal.valueOf(50)) > 0) {
            errors.add("标气流量必须在 (0, 50] L/min 之间");
        }
    }

    /** 时长覆盖：key ∈ 14 键白名单；multi_zero_check 仅零点类子集；数值键正整数秒（≥1）；两个百分比键走序列规则。 */
    private void validateDurationOverrides(PlanSaveDto dto, QualityControlTypeEnum qcEnum, List<String> errors) {
        Map<String, Object> overrides = dto.getDurationOverrides();
        if (overrides == null) {
            return;
        }
        boolean multiZero = qcEnum == QualityControlTypeEnum.MULTI_ZERO_CHECK;
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            String key = e.getKey();
            if (!DURATION_OVERRIDE_KEYS.contains(key)) {
                errors.add("时长参数不在白名单内: " + key);
                continue;
            }
            if (multiZero && !MULTI_ZERO_DURATION_KEYS.contains(key)) {
                errors.add("多仪器零点质控仅支持零点类时长参数: " + key);
                continue;
            }
            Object value = e.getValue();
            if ("multiPointPercents".equals(key) || "accuracyPointPercents".equals(key)) {
                if (!(value instanceof List) || !isAscendingPercentList((List<?>) value)) {
                    errors.add(key + " 必须是 0~1 严格升序、至少 2 项的序列");
                }
                continue;
            }
            if (!(value instanceof Integer) || (Integer) value < 1) {
                errors.add("时长参数 " + key + " 必须是正整数（秒）");
            }
        }
    }

    private static boolean isAscendingPercentList(List<?> list) {
        if (list.size() < 2) {
            return false;
        }
        Double prev = null;
        for (Object item : list) {
            if (!(item instanceof Number)) {
                return false;
            }
            double v = ((Number) item).doubleValue();
            if (v < 0d || v > 1d) {
                return false;
            }
            if (prev != null && v <= prev) {
                return false;
            }
            prev = v;
        }
        return true;
    }

    /** 有效期：两字段可空；齐供时须 start &lt; end（FR-01-42）。 */
    private void validateWindow(PlanSaveDto dto, List<String> errors) {
        Instant start = parseInstant(dto.getPlanStartTime(), "有效期起", errors);
        Instant end = parseInstant(dto.getPlanEndTime(), "有效期止", errors);
        if (start != null && end != null && !start.isBefore(end)) {
            errors.add("有效期起必须早于有效期止");
        }
    }

    /**
     * 校准策略（03 设计 §3/§7.4）：可空=STANDARD（落库 NULL）；提供时值须在闭集内，
     * 且类型须属零点/跨度/多仪器零点（人工核查与其余类型不适用——裁决「不适用」而非默认 STANDARD）。
     */
    private void validateCalibrationPolicy(PlanSaveDto dto, QualityControlTypeEnum qcEnum, List<String> errors) {
        if (qcEnum == null) {
            return;
        }
        String policy = dto.getCalibrationPolicy();
        if (policy == null || policy.trim().isEmpty()) {
            return;
        }
        if (!CALIBRATION_POLICIES.contains(policy)) {
            errors.add("校准策略必须是 STANDARD/CALIBRATE_LOW_DRIFT 之一: " + policy);
            return;
        }
        if (!POLICY_APPLICABLE_TYPES.contains(qcEnum.getName())) {
            errors.add("该质控类型不支持校准策略（仅零点/跨度/多仪器零点可设）");
        }
    }

    /** 同日优先级（03 设计 §7.4）：可空=NONE（落库 NULL）；提供时须 ∈ NONE/LOW/HIGH。计划通用字段，不限类型。 */
    private void validateSameDayPriority(PlanSaveDto dto, List<String> errors) {
        String priority = dto.getSameDayPriority();
        if (priority == null || priority.trim().isEmpty()) {
            return;
        }
        if (!SAME_DAY_PRIORITIES.contains(priority)) {
            errors.add("同日优先级必须是 NONE/LOW/HIGH 之一: " + priority);
        }
    }

    /** RFC 3339 解析（可空字段空串视为未填）；解析失败逐条报错返回 null。 */
    private static Instant parseInstant(String text, String label, List<String> errors) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return parseApiInstant(text);
        } catch (DateTimeParseException e) {
            errors.add(label + "必须是 RFC 3339 带时区偏移的时刻（如 2026-09-23T16:34:56+08:00）: " + text);
            return null;
        }
    }

    /**
     * API 时刻字段唯一解析口径：RFC 3339 带偏移绝对时刻，+08:00 与 Z 两形态等价
     * （均为标准表示）；拒绝无时区裸墙钟——接收方只能猜时区，正是历史漂移缺陷的根源。
     * 保存链（校验器与服务装配）共用，不各自为政。
     */
    public static Instant parseApiInstant(String text) {
        return OffsetDateTime.parse(text.trim()).toInstant();
    }
}
