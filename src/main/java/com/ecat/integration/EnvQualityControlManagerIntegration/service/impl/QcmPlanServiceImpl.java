package com.ecat.integration.EnvQualityControlManagerIntegration.service.impl;

import com.ecat.core.EcatCore;
import com.ecat.core.Utils.DateTimeUtils;
import com.ecat.integration.EnvCalibrationComposerIntegration.EnvCalibrationComposerIntegration;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorType;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanEstimateDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanEstimateResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.QcmPlanScheduler;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.ScheduleCalculator;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.ScheduleSpec;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.ScheduleSpecs;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.PlanParamValidator;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.PlanRequestAssembler;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmPlanService;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 计划服务实现：状态机（FR-01-15..19）/ 保存重算 next_fire_time（FR-01-16/43）/
 * 立即执行共用装配（{@link PlanRequestAssembler}）/ 阶段预估走 composer 只读能力（FR-01-32 同源）。
 *
 * <p>时钟注入：next_fire_time 计算与 ONCE 时序判断共用同一 {@link Clock}（纯 instant 源），
 * 测试固定时钟保证确定性；墙钟语义（锚点日/摘要文案）的时区统一取 ecat 平台时区
 * {@link DateTimeUtils#getZone()}，不各自散落取 JVM 默认。</p>
 *
 * @author coffee
 */
@Service
public class QcmPlanServiceImpl implements IQcmPlanService {

    private static final Logger log = LoggerFactory.getLogger(QcmPlanServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SUMMARY_MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter SUMMARY_SHORT_DATETIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String COMPOSER_INTEGRATION_ID = "integration-env-calibration-composer";

    private final QcmPlanMapper planMapper;
    private final QcmPlanScheduler scheduler;
    private final QcmExecutionOrchestrator orchestrator;
    private final PlanParamValidator validator;
    private final EcatCore core;
    private final Clock clock;

    @Autowired
    public QcmPlanServiceImpl(QcmPlanMapper planMapper, QcmPlanScheduler scheduler,
                              QcmExecutionOrchestrator orchestrator, PlanParamValidator validator, EcatCore core) {
        this(planMapper, scheduler, orchestrator, validator, core, Clock.systemDefaultZone());
    }

    /** 测试构造：注入固定时钟。 */
    public QcmPlanServiceImpl(QcmPlanMapper planMapper, QcmPlanScheduler scheduler,
                              QcmExecutionOrchestrator orchestrator, PlanParamValidator validator, EcatCore core,
                              Clock clock) {
        this.planMapper = planMapper;
        this.scheduler = scheduler;
        this.orchestrator = orchestrator;
        this.validator = validator;
        this.core = core;
        this.clock = clock;
    }

    @Override
    public QcmPlan save(PlanSaveDto dto, String caller) {
        requireCaller(caller);
        List<String> errors = validator.validate(dto);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors));
        }
        Instant now = clock.instant();
        QcmPlan plan = toEntity(dto);
        plan.setUpdatedBy(caller);
        plan.setUpdateTime(now);
        if (dto.getId() == null) {
            plan.setCreatedBy(caller);
            plan.setCreateTime(now);
            plan.setStatus("ACTIVE");
            plan.setNextFireTime(computeNextFire(plan, now));
            planMapper.insert(plan);
            if (isOnceImmediate(dto)) {
                // 「立刻」模式创建即触发一次（FR-01-15）：编排器执行异步落库，ONCE→FINISHED 由其收口（D11）
                log.info("qcm 计划 {}({}) 为一次性立刻执行，创建即触发", plan.getId(), plan.getPlanName());
                orchestrator.triggerExecution(PlanRequestAssembler.assemble(plan), TriggerSource.MANUAL, caller);
            }
        } else {
            QcmPlan existing = requirePlan(dto.getId());
            if ("FINISHED".equals(existing.getStatus())) {
                throw new IllegalArgumentException("已完成（FINISHED）的计划不可编辑，仅可删除（FR-01-19）");
            }
            plan.setId(existing.getId());
            plan.setStatus(existing.getStatus());
            // 编辑=整行替换后从当前时刻重算（FR-01-19/43）；PAUSED 置空（FR-01-17）
            plan.setNextFireTime("ACTIVE".equals(existing.getStatus()) ? computeNextFire(plan, now) : null);
            planMapper.updateAll(plan);
        }
        scheduler.notifyPlanChanged(plan.getId());
        return decorate(planMapper.selectById(plan.getId()));
    }

    @Override
    public void changeStatus(Long id, String targetStatus, String caller) {
        requireCaller(caller);
        if (!"ACTIVE".equals(targetStatus) && !"PAUSED".equals(targetStatus)) {
            throw new IllegalArgumentException("目标状态必须是 ACTIVE 或 PAUSED");
        }
        QcmPlan plan = requirePlan(id);
        if ("FINISHED".equals(plan.getStatus())) {
            throw new IllegalArgumentException("已完成（FINISHED）的计划不可变更状态，仅可删除");
        }
        if (plan.getStatus().equals(targetStatus)) {
            throw new IllegalArgumentException("计划已处于 " + targetStatus + " 状态，无需变更");
        }
        planMapper.updateStatus(id, targetStatus, caller);
        if ("PAUSED".equals(targetStatus)) {
            planMapper.updateNextFireTime(id, null, null);
        } else {
            // 启用=从当前时刻起算（不追补，FR-01-16）
            planMapper.updateNextFireTime(id, computeNextFire(plan, clock.instant()), null);
        }
        scheduler.notifyPlanChanged(id);
    }

    @Override
    public void delete(Long id, String caller) {
        requireCaller(caller);
        requirePlan(id);
        planMapper.deleteById(id);
        scheduler.notifyPlanChanged(id);
    }

    @Override
    public BatchResult runNow(Long id, String caller) {
        requireCaller(caller);
        QcmPlan plan = requirePlan(id);
        if ("FINISHED".equals(plan.getStatus())) {
            throw new IllegalArgumentException("已完成（FINISHED）的计划不可执行");
        }
        QcExecutionRequest request = PlanRequestAssembler.assemble(plan);
        return orchestrator.triggerExecution(request, TriggerSource.MANUAL, caller);
    }

    @Override
    public List<QcmPlan> selectList(QcmPlan query) {
        // 原地富化、返回 mapper 容器引用（同仓 report findPage 惯例）：分页时 mapper 返回
        // PageHelper Page（承载 COUNT total），拷贝成新 List 会丢 total——ruoyi getDataTable
        // 的 PageInfo 对普通 List 退化为 list.size()=pageSize，前端总数错（total=页行数缺陷）
        List<QcmPlan> plans = planMapper.selectList(query);
        for (QcmPlan plan : plans) {
            decorate(plan);
        }
        return plans;
    }

    @Override
    public QcmPlan selectById(Long id) {
        return decorate(requirePlan(id));
    }

    @Override
    public PlanEstimateResult estimate(PlanEstimateDto dto) {
        if (dto == null || dto.getQcType() == null) {
            throw new IllegalArgumentException("质控类型不能为空");
        }
        QualityControlTypeEnum qcEnum = enumByName(dto.getQcType());
        List<String> instruments = dto.getInstruments();
        // multi_zero_check 开多仪器预估（G5：1~4 台，与 PlanParamValidator 多仪器白名单同口径）；
        // 其余类型保持单仪器 API 语义
        boolean multiZero = qcEnum == QualityControlTypeEnum.MULTI_ZERO_CHECK;
        if (instruments == null || instruments.isEmpty()
                || (!multiZero && instruments.size() != 1)
                || (multiZero && instruments.size() > 4)) {
            throw new IllegalArgumentException(multiZero ? "multi_zero_check 预估必须指定 1~4 台仪器" : "预估必须指定 1 台仪器");
        }
        // 与执行同一条 flowParams 组装路径（FR-01-32 预估=实际，禁止双源）
        QcExecutionRequest req = QcExecutionRequest.builder()
                .qcType(dto.getQcType())
                .instruments(instruments)
                .concentrationPpb(dto.getConcentrationPpb())
                .pointPercents(dto.getPointPercents())
                .flowRateLpm(dto.getFlowRateLpm())
                .durationOverrides(dto.getDurationOverrides())
                .build();
        Map<String, Object> flowParams = QcmExecutionOrchestrator.buildFlowParams(req, qcEnum);
        EnvCalibrationComposerIntegration composer = (EnvCalibrationComposerIntegration) composer();
        // MULTI_ZERO_CHECK 下 gas 单参不参与设备解析（composer 契约：构线走 flowParams.instruments），
        // 取首气仅是签名占位；其余类型 gas 定位气路设备
        String gas = LogicDeviceBindingIds.composerGasKeyFromParameterName(instruments.get(0));
        List<PhaseInfo> phases = composer.estimatePhases(
                ExecutorType.getEnum(qcEnum.getClassName()), gas, flowParams.isEmpty() ? null : flowParams);

        List<PlanEstimateResult.PhaseEstimate> items = new ArrayList<>();
        long total = 0L;
        int recovery = 0;
        for (PhaseInfo phase : phases) {
            if (phase == null) {
                continue;
            }
            items.add(new PlanEstimateResult.PhaseEstimate(
                    phase.getId(), phase.getDisplayName(), phase.getEstimatedSeconds()));
            total += phase.getEstimatedSeconds();
            if ("recovery".equals(phase.getId())) {
                recovery = phase.getEstimatedSeconds();
            }
        }
        List<PlanEstimateResult.PointEstimate> points = estimatePoints(qcEnum, instruments.get(0), flowParams);
        return new PlanEstimateResult(items, total, recovery, points);
    }

    /**
     * 浓度点预估（G-REQ-9）：多点/准确度走 composer estimatePoints 只读能力（同源，
     * 禁止 qcm 复制 percent×range 公式）；零点单点 0；跨度单点取 flowParams 的
     * spanConcentrationPpb（用户值或默认）；其余类型空表。composer 返回的
     * PointConcentration 在此映射为 qcm 自有 PointEstimate（@Service bean 签名
     * 不得引用 composer 类型，G-BUG-13），单位规则后端统一：CO → ppm（ppb/1000，
     * 至多 3 位小数去尾零），其余 → ppb（整数或去尾零）。
     */
    private List<PlanEstimateResult.PointEstimate> estimatePoints(
            QualityControlTypeEnum qcEnum, String instrument, Map<String, Object> flowParams) {
        boolean co = "CO".equalsIgnoreCase(instrument);
        String code = qcEnum.getCode();
        if (QualityControlTypeEnum.MULTI_CHECK.getCode().equals(code)
                || QualityControlTypeEnum.ACCURACY_CHECK.getCode().equals(code)) {
            EnvCalibrationComposerIntegration composer = (EnvCalibrationComposerIntegration) composer();
            String gas = LogicDeviceBindingIds.composerGasKeyFromParameterName(instrument);
            List<EnvCalibrationComposerIntegration.PointConcentration> source = composer.estimatePoints(
                    ExecutorType.getEnum(qcEnum.getClassName()), gas,
                    flowParams.isEmpty() ? null : flowParams);
            List<PlanEstimateResult.PointEstimate> points = new ArrayList<>(source.size());
            for (EnvCalibrationComposerIntegration.PointConcentration point : source) {
                points.add(toPointEstimate(point.percent, point.concentrationPpb, co));
            }
            return points;
        }
        if (QualityControlTypeEnum.ZERO_CHECK.getCode().equals(code)) {
            return Collections.singletonList(toPointEstimate(0.0d, 0.0d, co));
        }
        if (QualityControlTypeEnum.SPAN_CHECK.getCode().equals(code)) {
            // 绝对标气浓度无百分语义（percent=null）；值已由 buildFlowParams 归一（用户值或默认）
            float spanPpb = (Float) flowParams.get("spanConcentrationPpb");
            return Collections.singletonList(toPointEstimate(null, spanPpb, co));
        }
        return Collections.emptyList();
    }

    /** composer 点 → qcm 展示点：单位换算（CO→ppm / 其余→ppb）+ 去尾零格式化。 */
    private static PlanEstimateResult.PointEstimate toPointEstimate(Double percent, double concentrationPpb, boolean co) {
        String unit = co ? "ppm" : "ppb";
        java.math.BigDecimal value = co
                ? java.math.BigDecimal.valueOf(concentrationPpb).divide(java.math.BigDecimal.valueOf(1000), 3,
                        java.math.RoundingMode.DOWN)
                : java.math.BigDecimal.valueOf(concentrationPpb).setScale(3, java.math.RoundingMode.DOWN);
        return new PlanEstimateResult.PointEstimate(percent, concentrationPpb,
                value.stripTrailingZeros().toPlainString(), unit);
    }

    @Override
    public String scheduleSummary(QcmPlan plan) {
        JsonNode config = parseConfig(plan);
        String time = String.format("%02d:%02d", intOr(config, "hour", 0), intOr(config, "minute", 0));
        String sinceStart = planStartSuffix(plan);
        switch (plan.getScheduleType()) {
            case "DAILY":
                // 存量类型（新 UI 不再产出），调度行为不动，仅展示译作「每 1 天」
                return "每 1 天 " + time + sinceStart;
            case "WEEKLY":
                return "每 " + intOr(config, "intervalWeeks", 1) + " 周 "
                        + joinWeekdays(config) + " " + time + sinceStart;
            case "MONTHLY":
                return "每月 " + joinMonthDays(config) + " 日 " + time + sinceStart;
            case "ONCE":
                return onceSummary(config);
            case "INTERVAL":
                return "每 " + intervalDaysOrThrow(config) + " 天 " + time + sinceStart;
            default:
                throw new IllegalArgumentException("未知调度类型: " + plan.getScheduleType());
        }
    }

    /** 「起MM-DD」后缀：有效期起有值才拼（平台时区墙钟日）；ONCE 触发时刻即自身，不拼此段。 */
    private static String planStartSuffix(QcmPlan plan) {
        if (plan.getPlanStartTime() == null) {
            return "";
        }
        return " 起" + SUMMARY_MONTH_DAY.format(plan.getPlanStartTime().atZone(DateTimeUtils.getZone()));
    }

    /** INTERVAL 摘要间隔天数：行内缺失/非法即坏行，明确抛错（同 onceSummary 对缺 onceAt 的处理，不打印「每 0 天」掩盖）。 */
    private static int intervalDaysOrThrow(JsonNode config) {
        JsonNode node = config.get("intervalDays");
        if (node == null || node.isNull() || !node.isInt() || node.asInt() < 1) {
            throw new IllegalArgumentException("间隔天数计划缺少 intervalDays，无法生成调度摘要");
        }
        return node.asInt();
    }

    // ==================== 私有辅助 ====================

    private static boolean isOnceImmediate(PlanSaveDto dto) {
        return "ONCE".equals(dto.getScheduleType()) && "IMMEDIATE".equals(dto.getOnceMode());
    }

    /** dto → 实体：jsonb 列组 JSON 串（稀疏列空值落 null）。 */
    private static QcmPlan toEntity(PlanSaveDto dto) {
        QcmPlan plan = new QcmPlan();
        plan.setId(dto.getId());
        plan.setPlanName(dto.getPlanName().trim());
        plan.setQcType(dto.getQcType());
        plan.setInstruments(toJsonArray(dto.getInstruments()));
        plan.setScheduleType(dto.getScheduleType());
        plan.setScheduleConfig(toScheduleConfigJson(dto));
        plan.setConcentrationPpb(dto.getConcentrationPpb());
        plan.setFlowRateLpm(dto.getFlowRateLpm());
        plan.setDurationOverrides(dto.getDurationOverrides() == null || dto.getDurationOverrides().isEmpty()
                ? null : toJson(dto.getDurationOverrides()));
        plan.setPointPercents(dto.getPointPercents() == null || dto.getPointPercents().isEmpty()
                ? null : toJson(dto.getPointPercents()));
        // 未提供即落 NULL 列（NULL=STANDARD / NULL=NONE 的缺省语义由列承载，不落空串）
        plan.setCalibrationPolicy(blankToNull(dto.getCalibrationPolicy()));
        plan.setSameDayPriority(blankToNull(dto.getSameDayPriority()));
        plan.setPlanStartTime(parseOrNull(dto.getPlanStartTime()));
        plan.setPlanEndTime(parseOrNull(dto.getPlanEndTime()));
        return plan;
    }

    /** 空/白串归 null（与校验器「空=未填」同一口径，避免空串落库绕过 NULL 缺省语义）。 */
    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static String toScheduleConfigJson(PlanSaveDto dto) {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("hour", dto.getHour());
        config.put("minute", dto.getMinute());
        Instant planStart = parseOrNull(dto.getPlanStartTime());
        if ("INTERVAL".equals(dto.getScheduleType())) {
            // 锚点=有效期起墙钟日：写入 planStart 原值，calculator 只取其 zone 墙钟日
            // （同一 Instant 在任一 zone 必得同一日期差，时区无关）；planStart 由校验器保证非空
            config.put("intervalDays", dto.getIntervalDays());
            config.put("anchorDate", planStart.toString());
        }
        if ("WEEKLY".equals(dto.getScheduleType())) {
            // 恒写 intervalWeeks（空=每 1 周；存量无键行的解析兼容点在 ScheduleSpecs）；
            // 周相位锚=含 planStart 的周，有 planStart 即落 anchorDate（=1 不参与计算但留作编辑基准）
            config.put("intervalWeeks", dto.getIntervalWeeks() == null ? 1 : dto.getIntervalWeeks());
            if (planStart != null) {
                config.put("anchorDate", planStart.toString());
            }
        }
        if (dto.getWeekdays() != null && !dto.getWeekdays().isEmpty()) {
            ArrayNode weekdays = config.putArray("weekdays");
            dto.getWeekdays().forEach(weekdays::add);
        }
        if (dto.getMonthDays() != null && !dto.getMonthDays().isEmpty()) {
            ArrayNode monthDays = config.putArray("monthDays");
            dto.getMonthDays().forEach(monthDays::add);
        }
        if (dto.getOnceMode() != null) {
            config.put("onceMode", dto.getOnceMode());
        }
        if (dto.getOnceAt() != null && !dto.getOnceAt().trim().isEmpty()) {
            // config 内嵌时刻统一存转换后的 UTC instant 串（与 anchorDate 同形态），
            // ScheduleSpecs 按 Instant.parse 读取——API 偏移原串不得入库
            config.put("onceAt", parseOrNull(dto.getOnceAt()).toString());
        }
        return config.toString();
    }

    /** ACTIVE 计划的下次触发时刻：ONCE·IMMEDIATE 无调度点返回 null，其余交统一计算器。 */
    private Instant computeNextFire(QcmPlan plan, Instant now) {
        if ("ONCE".equals(plan.getScheduleType())) {
            JsonNode config = parseConfig(plan);
            if (!config.hasNonNull("onceAt")) {
                return null;
            }
        }
        ScheduleSpec spec = ScheduleSpecs.fromConfig(plan.getScheduleType(), plan.getScheduleConfig(),
                plan.getPlanStartTime(), plan.getPlanEndTime());
        Optional<Instant> next = ScheduleCalculator.nextFire(spec, now, DateTimeUtils.getZone());
        return next.orElse(null);
    }

    private QcmPlan decorate(QcmPlan plan) {
        if (plan != null) {
            plan.setScheduleSummary(scheduleSummary(plan));
        }
        return plan;
    }

    private QcmPlan requirePlan(Long id) {
        QcmPlan plan = planMapper.selectById(id);
        if (plan == null) {
            throw new IllegalArgumentException("计划不存在: " + id);
        }
        return plan;
    }

    private static void requireCaller(String caller) {
        if (caller == null || caller.trim().isEmpty()) {
            throw new IllegalArgumentException("caller 不能为空（FR-05-10 操作留痕）");
        }
    }

    private static QualityControlTypeEnum enumByName(String name) {
        for (QualityControlTypeEnum e : QualityControlTypeEnum.values()) {
            if (e.getName().equals(name)) {
                return e;
            }
        }
        throw new IllegalArgumentException("不支持的质控类型: " + name);
    }

    /** 返回 Object（Spring bean 签名不得引用 composer 类型，见 QcmExecutionOrchestrator 同名方法注释）。 */
    private Object composer() {
        return (EnvCalibrationComposerIntegration) core.getIntegrationRegistry()
                .getIntegration(COMPOSER_INTEGRATION_ID);
    }

    private JsonNode parseConfig(QcmPlan plan) {
        try {
            JsonNode node = plan.getScheduleConfig() == null ? null : MAPPER.readTree(plan.getScheduleConfig());
            return node == null ? MAPPER.createObjectNode() : node;
        } catch (Exception e) {
            throw new IllegalArgumentException("schedule_config 非法: " + plan.getScheduleConfig(), e);
        }
    }

    private static int intOr(JsonNode config, String field, int defaultValue) {
        JsonNode node = config.get(field);
        return node == null || node.isNull() ? defaultValue : node.asInt();
    }

    /** 每周摘要星期段：按周一~周日顺序（FR-01-21），如「周一、周三」。 */
    private static String joinWeekdays(JsonNode config) {
        TreeSet<Integer> days = new TreeSet<>();
        JsonNode node = config.get("weekdays");
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                days.add(item.asInt());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Integer d : days) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(PlanParamValidator.WEEKDAY_NAMES.get(d - 1));
        }
        return sb.toString();
    }

    /** 每月摘要日段：数字升序中点连，如「1·15」。 */
    private static String joinMonthDays(JsonNode config) {
        TreeSet<Integer> days = new TreeSet<>();
        JsonNode node = config.get("monthDays");
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                days.add(item.asInt());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Integer d : days) {
            if (sb.length() > 0) {
                sb.append("·");
            }
            sb.append(d);
        }
        return sb.toString();
    }

    /** 一次性摘要：立刻执行 / 指定时刻（平台时区墙钟 MM-dd HH:mm）。 */
    private String onceSummary(JsonNode config) {
        if ("IMMEDIATE".equals(config.path("onceMode").asText(null))) {
            return "一次性 · 立刻执行";
        }
        JsonNode onceAt = config.get("onceAt");
        if (onceAt == null || onceAt.isNull()) {
            throw new IllegalArgumentException("一次性计划缺少 onceAt，无法生成调度摘要");
        }
        ZonedDateTime wall = Instant.parse(onceAt.asText()).atZone(DateTimeUtils.getZone());
        return "一次性 " + SUMMARY_SHORT_DATETIME.format(wall);
    }

    private static String toJsonArray(List<String> values) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array.toString();
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + value, e);
        }
    }

    private static Instant parseOrNull(String text) {
        return text == null || text.trim().isEmpty() ? null : PlanParamValidator.parseApiInstant(text);
    }
}
