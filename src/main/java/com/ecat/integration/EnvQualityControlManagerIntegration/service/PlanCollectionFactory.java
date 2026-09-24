package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.CollectionCreateDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.CollectionCreateResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.FlowDefaults;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集合快捷方式工厂（03 设计 §6）：日常/周核查模板一次组装「同时零点 + 逐气跨度」
 * 原子 plan 行并事务性批量落库。plan 行=最小调度单元=最小执行单元（不引入组合任务
 * 实体），串行化由槽位表错开表达——零点 0:45 + 跨度 O3/CO/NOx/SO2 = 1:45/2:45/3:45/4:45，
 * 勾选子集只裁行不移位（不同集合间同时刻语义稳定）；溢出互斥走既有 BUSY_CONFLICT
 * 留痕，不在本层兜。
 *
 * <p>逐行复用 {@link IQcmPlanService#save}（校验/computeNextFire/notifyPlanChanged 单一
 * 事实源），本类只补三件事：</p>
 * <ul>
 *   <li>模板展开默认值：日常 LOW / 周 HIGH、策略统一预填（null 透传=落 NULL=STANDARD）、
 *       跨度浓度按 FlowDefaults 预填（CO 量级不同）、时长走 composer 默认（不预填）；</li>
 *   <li>先全量校验后落库：任一行非法一次抛全量错误（带行号与行名），零 insert；</li>
 *   <li>间隔预警：前一行 预估时长+15 分钟缓冲 覆盖下一行开始时刻（触及
 *       边界=覆盖）→ 非阻断，未携 force 时零落库返回 warnings。预估不可用的行只进
 *       estimateFailures 提示清单——预警是建议性护栏，预估子系统不可用不阻断建计划。</li>
 * </ul>
 *
 * @author coffee
 */
@Service
public class PlanCollectionFactory {

    /** 间隔预警缓冲：预估时长 + 15 分钟 >= 下一行开始时刻即预警。 */
    static final int WARNING_MARGIN_SECONDS = 900;
    /** 零点行覆盖键（RowOverride.rowKey 定位零点行）。 */
    static final String ZERO_ROW_KEY = "zero";

    /**
     * 跨度槽位表（设计 §6）：气种 canonical 槽位序与错开时刻。LinkedHashMap 保序即
     * 行序与零点行 instruments 序（乱序输入在此规整）。
     */
    private static final Map<String, int[]> SPAN_SLOTS = new LinkedHashMap<>();

    /** 零点槽位（同时零点时刻）。 */
    private static final int[] ZERO_SLOT = {0, 45};

    /** 展示名（FR-01-28 代码键↔展示名：NO2 质控走 NOx 通道）。 */
    private static final Map<String, String> GAS_DISPLAY_NAMES;

    static {
        SPAN_SLOTS.put("O3", new int[]{1, 45});
        SPAN_SLOTS.put("CO", new int[]{2, 45});
        SPAN_SLOTS.put("NO2", new int[]{3, 45});
        SPAN_SLOTS.put("SO2", new int[]{4, 45});
        Map<String, String> names = new LinkedHashMap<>();
        names.put("O3", "O3");
        names.put("CO", "CO");
        names.put("NO2", "NOx");
        names.put("SO2", "SO2");
        GAS_DISPLAY_NAMES = Collections.unmodifiableMap(names);
    }

    private final IQcmPlanService planService;
    private final PlanParamValidator validator;
    private final PlanRowDurationEstimator estimator;

    @Autowired
    public PlanCollectionFactory(IQcmPlanService planService, PlanParamValidator validator,
                                 PlanRowDurationEstimator estimator) {
        this.planService = planService;
        this.validator = validator;
        this.estimator = estimator;
    }

    /**
     * 模板展开 + 事务性批量落库。行级非法 → 一次抛全量错误（零落库）；落库中途失败 →
     * 异常传播由 @Transactional 整体回滚（先插的行不残留）。
     *
     * @return created=false 表示被间隔预警拦截（零落库，携 warnings 供确认后 force 重提）；
     *         force 越过预警时 created=true 且 warnings 随行返回
     */
    @Transactional
    public CollectionCreateResult createCollection(CollectionCreateDto dto, String caller) {
        requireCaller(caller);
        validateTemplateShape(dto);
        List<PlanSaveDto> rows = assembleRows(dto);

        // 先全量校验后落库：错误一次带全，避免「建了 3 行才报第 4 行非法」
        List<String> invalid = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            for (String error : validator.validate(rows.get(i))) {
                invalid.add("第" + (i + 1) + "行[" + rows.get(i).getPlanName() + "] " + error);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", invalid));
        }

        SlotWarningCheck check = checkSlotOverflow(rows);
        if (!check.warnings.isEmpty() && !Boolean.TRUE.equals(dto.getForce())) {
            return new CollectionCreateResult(false, Collections.emptyList(),
                    check.warnings, check.estimateFailures);
        }
        List<QcmPlan> plans = new ArrayList<>(rows.size());
        for (PlanSaveDto row : rows) {
            plans.add(planService.save(row, caller));
        }
        return new CollectionCreateResult(true, plans, check.warnings, check.estimateFailures);
    }

    /** 模板形态校验（模板特有必填/闭集；行内字段值域由 PlanParamValidator 兜，此处不重复）。 */
    private static void validateTemplateShape(CollectionCreateDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (dto.getTemplate() == null || dto.getTemplate().trim().isEmpty()) {
            throw new IllegalArgumentException("template 不能为空（DAILY / WEEKLY）");
        }
        boolean daily = "DAILY".equals(dto.getTemplate());
        boolean weekly = "WEEKLY".equals(dto.getTemplate());
        if (!daily && !weekly) {
            throw new IllegalArgumentException("template 仅支持 DAILY / WEEKLY: " + dto.getTemplate());
        }
        if (dto.getCollectionName() == null || dto.getCollectionName().trim().isEmpty()) {
            throw new IllegalArgumentException("collectionName 不能为空");
        }
        if (dto.getCollectionName().length() > 100) {
            throw new IllegalArgumentException("collectionName 长度不得超 100");
        }
        if (dto.getInstruments() == null || dto.getInstruments().isEmpty()) {
            throw new IllegalArgumentException("至少勾选一种气（SO2 / NO2 / CO / O3）");
        }
        Set<String> seen = new HashSet<>();
        for (String gas : dto.getInstruments()) {
            if (!SPAN_SLOTS.containsKey(gas)) {
                throw new IllegalArgumentException("不支持的气种: " + gas);
            }
            if (!seen.add(gas)) {
                throw new IllegalArgumentException("气种重复勾选: " + gas);
            }
        }
        validateScheduleShape(dto);
        validateRowOverrides(dto);
    }

    /**
     * 统一调度形态校验（按 scheduleType，与 template 解耦）：集合三值闭集
     * INTERVAL/WEEKLY/MONTHLY——不再产出 DAILY（按天语义走 INTERVAL）；ONCE 显式拒绝——
     * 共享气路套件须逐行错峰，单时刻无法承载（UI 已隐藏该选项，此处防直调 API 绕过）。
     * 各类型必填项与锚点依赖在此前置拦截（带字段名），值域由行级 PlanParamValidator 兜，此处不重复。
     */
    private static void validateScheduleShape(CollectionCreateDto dto) {
        if (dto.getScheduleType() == null || dto.getScheduleType().trim().isEmpty()) {
            throw new IllegalArgumentException("scheduleType 不能为空（INTERVAL / WEEKLY / MONTHLY）");
        }
        switch (dto.getScheduleType()) {
            case "INTERVAL":
                if (dto.getIntervalDays() == null) {
                    throw new IllegalArgumentException("INTERVAL 调度必须提供 intervalDays（1-31）");
                }
                if (isBlank(dto.getPlanStartTime())) {
                    throw new IllegalArgumentException("INTERVAL 调度必须提供 planStartTime（间隔锚点日由其派生）");
                }
                break;
            case "WEEKLY":
                if (dto.getWeekdays() == null || dto.getWeekdays().isEmpty()) {
                    throw new IllegalArgumentException("WEEKLY 调度必须提供 weekdays（1=周一..7=周日）");
                }
                if (dto.getIntervalWeeks() != null && dto.getIntervalWeeks() > 1
                        && isBlank(dto.getPlanStartTime())) {
                    throw new IllegalArgumentException("隔周数大于 1 时必须提供 planStartTime（周相位锚点由其派生）");
                }
                break;
            case "MONTHLY":
                if (dto.getMonthDays() == null || dto.getMonthDays().isEmpty()) {
                    throw new IllegalArgumentException("MONTHLY 调度必须提供 monthDays（1-31）");
                }
                break;
            case "ONCE":
                throw new IllegalArgumentException("集合不支持一次性调度：共享气路套件须逐行错峰，单时刻无法承载；"
                        + "如需某天跑一次全套，请用按天（间隔 1 天）+ 开始日期=当天 + 有效期止=当天");
            default:
                throw new IllegalArgumentException(
                        "scheduleType 仅支持 INTERVAL / WEEKLY / MONTHLY（集合不再产出 DAILY）: "
                                + dto.getScheduleType());
        }
    }

    /** 空串语义统一：null 或纯空白都视为未填。 */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void validateRowOverrides(CollectionCreateDto dto) {
        if (dto.getRows() == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (CollectionCreateDto.RowOverride override : dto.getRows()) {
            String key = override.getRowKey();
            boolean zero = ZERO_ROW_KEY.equals(key);
            if (!zero && !SPAN_SLOTS.containsKey(key)) {
                throw new IllegalArgumentException("rows[].rowKey 仅支持 zero 或气种代码键: " + key);
            }
            if (!zero && !dto.getInstruments().contains(key)) {
                throw new IllegalArgumentException("rows[].rowKey 指向未勾选气种: " + key);
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("rows[].rowKey 重复: " + key);
            }
        }
    }

    /** 模板展开为原子 plan 行（零点 + 逐气跨度，槽位序），并应用稀疏行覆盖。 */
    private List<PlanSaveDto> assembleRows(CollectionCreateDto dto) {
        // 槽位序规整：输入乱序不影响行序、零点行 instruments 序与跨度槽位语义
        List<String> selected = new ArrayList<>(SPAN_SLOTS.keySet());
        selected.retainAll(new HashSet<>(dto.getInstruments()));
        boolean allFour = selected.size() == SPAN_SLOTS.size();
        boolean weekly = "WEEKLY".equals(dto.getTemplate());
        String defaultPriority = weekly ? "HIGH" : "LOW";

        List<PlanSaveDto> rows = new ArrayList<>();
        PlanSaveDto zero = new PlanSaveDto();
        zero.setPlanName(dto.getCollectionName() + "-" + zeroGasesLabel(selected, allFour) + "-零点");
        zero.setQcType(QualityControlTypeEnum.MULTI_ZERO_CHECK.getName());
        zero.setInstruments(selected);
        applySchedule(dto, zero);
        zero.setHour(ZERO_SLOT[0]);
        zero.setMinute(ZERO_SLOT[1]);
        zero.setSameDayPriority(defaultPriority);
        zero.setCalibrationPolicy(dto.getCalibrationPolicy());
        rows.add(zero);

        for (String gas : selected) {
            PlanSaveDto span = new PlanSaveDto();
            span.setPlanName(dto.getCollectionName() + "-" + GAS_DISPLAY_NAMES.get(gas) + "-跨度");
            span.setQcType(QualityControlTypeEnum.SPAN_CHECK.getName());
            span.setInstruments(Collections.singletonList(gas));
            applySchedule(dto, span);
            int[] slot = SPAN_SLOTS.get(gas);
            span.setHour(slot[0]);
            span.setMinute(slot[1]);
            span.setSameDayPriority(defaultPriority);
            span.setCalibrationPolicy(dto.getCalibrationPolicy());
            // 跨度浓度按执行侧默认预填（勾选即出值免逐行手填；CO 量级不同同源 FlowDefaults）
            span.setConcentrationPpb(BigDecimal.valueOf("CO".equals(gas)
                    ? FlowDefaults.DEFAULT_SPAN_CONCENTRATION_PPB_CO
                    : FlowDefaults.DEFAULT_SPAN_CONCENTRATION_PPB));
            rows.add(span);
        }
        applyRowOverrides(dto, rows);
        return rows;
    }

    /**
     * 统一调度展开：按 scheduleType 透传调度字段到行（集合仅 INTERVAL/WEEKLY/MONTHLY，
     * ONCE 已在形态校验显式拒绝），逐行 hour/minute 独立来自槽位/RowOverride——
     * 集合行有逐行错峰时刻，不从 planStartTime 派生时刻。
     */
    private static void applySchedule(CollectionCreateDto dto, PlanSaveDto row) {
        row.setScheduleType(dto.getScheduleType());
        row.setPlanStartTime(dto.getPlanStartTime());
        row.setPlanEndTime(dto.getPlanEndTime());
        switch (dto.getScheduleType()) {
            case "INTERVAL":
                // 锚点日由 save 服务端从 planStartTime 派生写入 config
                row.setIntervalDays(dto.getIntervalDays());
                break;
            case "WEEKLY":
                row.setWeekdays(dto.getWeekdays());
                row.setIntervalWeeks(dto.getIntervalWeeks());
                break;
            case "MONTHLY":
                row.setMonthDays(dto.getMonthDays());
                break;
            default:
                throw new IllegalArgumentException(
                        "scheduleType 仅支持 INTERVAL / WEEKLY / MONTHLY: " + dto.getScheduleType());
        }
    }

    private static void applyRowOverrides(CollectionCreateDto dto, List<PlanSaveDto> rows) {
        if (dto.getRows() == null) {
            return;
        }
        Map<String, PlanSaveDto> byRowKey = new HashMap<>();
        byRowKey.put(ZERO_ROW_KEY, rows.get(0));
        for (PlanSaveDto row : rows.subList(1, rows.size())) {
            byRowKey.put(row.getInstruments().get(0), row);
        }
        for (CollectionCreateDto.RowOverride override : dto.getRows()) {
            PlanSaveDto target = byRowKey.get(override.getRowKey());
            if (override.getHour() != null) {
                target.setHour(override.getHour());
            }
            if (override.getMinute() != null) {
                target.setMinute(override.getMinute());
            }
            if (override.getSameDayPriority() != null) {
                target.setSameDayPriority(override.getSameDayPriority());
            }
            if (override.getDurationOverrides() != null) {
                target.setDurationOverrides(override.getDurationOverrides());
            }
        }
    }

    /** 零点行气种段：四气全勾=「四气」，部分勾选=槽位序展示名「、」拼接。 */
    private static String zeroGasesLabel(List<String> selected, boolean allFour) {
        if (allFour) {
            return "四气";
        }
        StringBuilder sb = new StringBuilder();
        for (String gas : selected) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(GAS_DISPLAY_NAMES.get(gas));
        }
        return sb.toString();
    }

    /** 间隔预警结果载体：warnings 触发即非阻断拦截（force 可越过）；estimateFailures 仅提示。 */
    private static final class SlotWarningCheck {
        final List<String> warnings = new ArrayList<>();
        final List<String> estimateFailures = new ArrayList<>();
    }

    /**
     * 间隔预警（设计 §6）：行按开始时刻排序成链，前一行 预估时长+15 分钟缓冲
     * >= 下一行开始时刻（触及边界=覆盖）即预警。纯秒数算术——模板槽位同处凌晨段无跨
     * 午夜回绕；排序在拷贝上下标序上进行，不动模板行序。预估不可用的行进提示清单，
     * 以其为前驱的相邻对检查跳过（缺的是建议数据，不是硬前提）。
     */
    private SlotWarningCheck checkSlotOverflow(List<PlanSaveDto> rows) {
        SlotWarningCheck result = new SlotWarningCheck();
        List<Long> estimates = new ArrayList<>(rows.size());
        for (PlanSaveDto row : rows) {
            estimates.add(estimateOrNull(row, result));
        }
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(startSeconds(rows.get(a)), startSeconds(rows.get(b))));
        for (int k = 1; k < order.length; k++) {
            PlanSaveDto previous = rows.get(order[k - 1]);
            Long previousEstimate = estimates.get(order[k - 1]);
            PlanSaveDto current = rows.get(order[k]);
            if (previousEstimate == null) {
                continue;
            }
            long finishSec = startSeconds(previous) + previousEstimate + WARNING_MARGIN_SECONDS;
            if (finishSec >= startSeconds(current)) {
                result.warnings.add("行「" + previous.getPlanName() + "」（" + slotText(previous)
                        + " 开始，预估 " + previousEstimate / 60 + " 分钟）加 15 分钟缓冲将覆盖行「"
                        + current.getPlanName() + "」（" + slotText(current) + "）的开始时刻，确认无冲突可强制保存");
            }
        }
        return result;
    }

    private Long estimateOrNull(PlanSaveDto row, SlotWarningCheck sink) {
        try {
            return estimator.estimateTotalSeconds(row);
        } catch (Exception e) {
            sink.estimateFailures.add("行「" + row.getPlanName() + "」时长预估不可用（"
                    + e.getMessage() + "），该行间隔预警已跳过");
            return null;
        }
    }

    private static int startSeconds(PlanSaveDto row) {
        return row.getHour() * 3600 + row.getMinute() * 60;
    }

    private static String slotText(PlanSaveDto row) {
        return String.format("%02d:%02d", row.getHour(), row.getMinute());
    }

    private static void requireCaller(String caller) {
        if (caller == null || caller.trim().isEmpty()) {
            throw new IllegalArgumentException("caller 不能为空（FR-05-10 操作留痕）");
        }
    }
}
