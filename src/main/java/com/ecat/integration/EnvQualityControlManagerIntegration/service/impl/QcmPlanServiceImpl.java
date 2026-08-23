package com.ecat.integration.EnvQualityControlManagerIntegration.service.impl;

import com.ecat.core.EcatCore;
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
import java.time.ZoneId;
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
 * <p>时钟注入：next_fire_time 计算与 ONCE 时序判断共用同一 {@link Clock}，测试固定时钟保证确定性。</p>
 *
 * @author coffee
 */
@Service
public class QcmPlanServiceImpl implements IQcmPlanService {

    private static final Logger log = LoggerFactory.getLogger(QcmPlanServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 摘要文案时区：面向中文运维页面统一东八区墙钟。 */
    private static final ZoneId SUMMARY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SUMMARY_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
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
        QcmPlan plan = toEntity(dto, now);
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
                log.info("[诊断调试] qcm 计划 {}({}) 为一次性立刻执行，创建即触发", plan.getId(), plan.getPlanName());
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
        List<QcmPlan> plans = planMapper.selectList(query);
        List<QcmPlan> decorated = new ArrayList<>(plans.size());
        for (QcmPlan plan : plans) {
            decorated.add(decorate(plan));
        }
        return decorated;
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
        if (instruments == null || instruments.size() != 1) {
            throw new IllegalArgumentException("预估必须指定 1 台仪器");
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
        switch (plan.getScheduleType()) {
            case "DAILY":
                return "每天 " + time;
            case "WEEKLY":
                return "每周 " + joinWeekdays(config) + " " + time;
            case "MONTHLY":
                return "每月 " + joinMonthDays(config) + " 日 " + time;
            case "ONCE":
                return onceSummary(config);
            default:
                throw new IllegalArgumentException("未知调度类型: " + plan.getScheduleType());
        }
    }

    // ==================== 私有辅助 ====================

    private static boolean isOnceImmediate(PlanSaveDto dto) {
        return "ONCE".equals(dto.getScheduleType()) && "IMMEDIATE".equals(dto.getOnceMode());
    }

    /** dto → 实体：jsonb 列组 JSON 串（稀疏列空值落 null）。 */
    private static QcmPlan toEntity(PlanSaveDto dto, Instant now) {
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
        plan.setPlanStartTime(parseOrNull(dto.getPlanStartTime()));
        plan.setPlanEndTime(parseOrNull(dto.getPlanEndTime()));
        return plan;
    }

    private static String toScheduleConfigJson(PlanSaveDto dto) {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("hour", dto.getHour());
        config.put("minute", dto.getMinute());
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
            config.put("onceAt", dto.getOnceAt().trim());
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
        Optional<Instant> next = ScheduleCalculator.nextFire(spec, now, clock.getZone());
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

    /** 每月摘要日段：数字升序，如「1、15」。 */
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
                sb.append("、");
            }
            sb.append(d);
        }
        return sb.toString();
    }

    /** 一次性摘要：立刻执行 / 指定时刻（东八区墙钟）。 */
    private String onceSummary(JsonNode config) {
        if ("IMMEDIATE".equals(config.path("onceMode").asText(null))) {
            return "一次性 · 立刻执行";
        }
        JsonNode onceAt = config.get("onceAt");
        if (onceAt == null || onceAt.isNull()) {
            throw new IllegalArgumentException("一次性计划缺少 onceAt，无法生成调度摘要");
        }
        ZonedDateTime wall = Instant.parse(onceAt.asText()).atZone(SUMMARY_ZONE);
        return "一次性 · " + SUMMARY_DATETIME.format(wall);
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
        return text == null || text.trim().isEmpty() ? null : Instant.parse(text.trim());
    }
}
