package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import com.ecat.core.Utils.DateTimeUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 同日优先级压制判定（03 设计 §7.2）：SCHEDULED 触发链路在过 misfire/指纹闩后、受理前
 * 调用——本行 LOW 且「同类工作当日已有高优先级覆盖」时让位（整行 SKIPPED 留痕）。
 *
 * <p>判定语义：</p>
 * <ul>
 *   <li>仅 same_day_priority=LOW 的触发行才判（NONE/HIGH/未标记不让位，短路不查库）；</li>
 *   <li>检查类别两族由 qcType 静态映射（零点族=zero_check/multi_zero_check、跨度族=span_check，
 *       不新增配置）——零点与跨度证据价值独立，跨类不压；两族之外无同类语义，永不压制；</li>
 *   <li>候选 = ACTIVE + HIGH + 同族 + 气种集合相交（站内计划数量级小：ACTIVE 一次 SQL +
 *       Java 过滤，毫秒级预算；PAUSED 行被 ACTIVE 查询天然排除）；</li>
 *   <li>「今日有无触发点」按候选行配置经 {@link ScheduleCalculator} 纯算（今日 00:00–24:00
 *       含已过时刻——当日刚创建、next_fire_time 已被推走的行照样构成压制，不看该列）；
 *       有效期窗口由纯算内嵌的窗口约束自然排除；</li>
 *   <li>气种相交即整行让位（行是最小调度单元，不拆分执行——覆盖不全是配置自由度的自然代价）。</li>
 * </ul>
 *
 * <p>坏行防御：候选行 instruments/schedule_config 不可解析时 log.warn 跳过该候选
 * （与 SDK queryPlans 坏行整条跳过同惯例）——坏行既不构成压制，也不炸掉本次触发。</p>
 *
 * <p>时钟注入：Clock 仅提供 instant；「今日」墙钟日按 ecat 平台时区
 * {@link DateTimeUtils#getZone()} 取，与调度器/保存侧墙钟计算同源，固定 Clock 保证测试确定性。</p>
 *
 * @author coffee
 */
@Service
public class SameDaySuppressionJudge {

    private static final Logger log = LoggerFactory.getLogger(SameDaySuppressionJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 零点族：零点核查与多仪器零点核查同族互压（证据价值同源）。 */
    private static final Set<String> ZERO_FAMILY = new HashSet<>(Arrays.asList("zero_check", "multi_zero_check"));
    /** 跨度族：跨度核查同族互压。 */
    private static final Set<String> SPAN_FAMILY = Collections.singleton("span_check");

    private final QcmPlanMapper planMapper;
    private final Clock clock;

    @Autowired
    public SameDaySuppressionJudge(QcmPlanMapper planMapper) {
        this(planMapper, Clock.systemDefaultZone());
    }

    /** 测试构造：注入固定时钟。 */
    SameDaySuppressionJudge(QcmPlanMapper planMapper, Clock clock) {
        this.planMapper = planMapper;
        this.clock = clock;
    }

    /**
     * 判定触发行是否让位；让位返回压制方计划名（result_evaluation 注明用），不让位返回 empty。
     */
    public Optional<String> suppressedBy(QcmPlan firing) {
        if (!"LOW".equals(firing.getSameDayPriority())) {
            return Optional.empty();
        }
        CheckFamily family = familyOf(firing.getQcType());
        if (family == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        List<String> firingInstruments = parseInstruments(firing);
        QcmPlan filter = new QcmPlan();
        filter.setStatus("ACTIVE");
        List<QcmPlan> actives = planMapper.selectList(filter);
        if (actives == null) {
            return Optional.empty();
        }
        for (QcmPlan candidate : actives) {
            if (!"HIGH".equals(candidate.getSameDayPriority())) {
                continue;
            }
            if (familyOf(candidate.getQcType()) != family) {
                continue;
            }
            List<String> candidateInstruments = parseInstruments(candidate);
            if (Collections.disjoint(candidateInstruments, firingInstruments)) {
                continue;
            }
            if (firesToday(candidate, now)) {
                return Optional.of(candidate.getPlanName());
            }
        }
        return Optional.empty();
    }

    /** 检查类别两族映射；族外类型返回 null（无同类压制语义）。 */
    private static CheckFamily familyOf(String qcType) {
        if (ZERO_FAMILY.contains(qcType)) {
            return CheckFamily.ZERO;
        }
        if (SPAN_FAMILY.contains(qcType)) {
            return CheckFamily.SPAN;
        }
        return null;
    }

    /** 候选行配置「今日（00:00–24:00，含已过时刻）是否有触发点」：纯算最小严格晚于候选落在今日即有。 */
    private boolean firesToday(QcmPlan plan, Instant now) {
        ZoneId zone = DateTimeUtils.getZone();
        LocalDate today = now.atZone(zone).toLocalDate();
        // after 取昨日末一瞬：今日 00:00:00 的触发点也计入（含已过时刻边界）
        Instant after = today.atStartOfDay(zone).toInstant().minusNanos(1);
        Instant tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant();
        try {
            ScheduleSpec spec = ScheduleSpecs.fromConfig(plan.getScheduleType(), plan.getScheduleConfig(),
                    plan.getPlanStartTime(), plan.getPlanEndTime());
            Optional<Instant> next = ScheduleCalculator.nextFire(spec, after, zone);
            return next.isPresent() && next.get().isBefore(tomorrowStart);
        } catch (RuntimeException e) {
            log.warn("压制候选计划 {}({}) 调度配置不可解析，跳过该候选: {}",
                    plan.getId(), plan.getPlanName(), e.getMessage());
            return false;
        }
    }

    /** instruments JSON 数组 → 列表；坏行跳过候选由调用方语义覆盖（返回空列表即不相交）。 */
    private static List<String> parseInstruments(QcmPlan plan) {
        try {
            JsonNode node = MAPPER.readTree(plan.getInstruments());
            if (node == null || !node.isArray()) {
                throw new IllegalArgumentException("instruments is not a JSON array: " + plan.getInstruments());
            }
            List<String> instruments = new ArrayList<>();
            for (JsonNode item : node) {
                instruments.add(item.asText());
            }
            return instruments;
        } catch (Exception e) {
            log.warn("计划 {}({}) instruments 不可解析，按无气种处理: {}",
                    plan.getId(), plan.getPlanName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 检查类别（同族才可能构成压制）。 */
    private enum CheckFamily {
        ZERO, SPAN
    }
}
