package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 质控计划的调度规则描述（不可变值对象），供 {@link ScheduleCalculator#nextFire} 计算
 * 下次触发时刻，是调度器的唯一真相源。
 *
 * <p>应用场景：计划创建/编辑时由配置页提交构造，调度器每轮 tick 或恢复（restart/编辑后）
 * 以当前时刻为 after 重算 nextFire——「严格晚于 after」保证错过的不追补、从当前时刻续算。</p>
 *
 * <p>构造期拦截非法值（hour 0-23、minute 0-59、weekdays 值域 1-7、monthDays 值域 1-31、
 * 按类型的必填集合：WEEKLY 须非空 weekdays 且 intervalWeeks∈[1,52]（>1 另须 anchorDate）、
 * MONTHLY 须非空 monthDays、ONCE 须 onceAt、
 * INTERVAL 须 intervalDays∈[1,31] 且 anchorDate 非空），
 * 非法直接抛 {@link IllegalArgumentException}，计算函数内不重复判。</p>
 *
 * <p>有效期窗口 planStartTime/planEndTime 为闭区间：候选 &lt; planStart 直接跳过，
 * 候选 &gt; planEnd 不再触发；两者可空表示该侧无界。</p>
 *
 * @author coffee
 */
@Value
public class ScheduleSpec {

    ScheduleType type;
    int hour;
    int minute;
    /** 间隔天数（1..31），仅 INTERVAL 使用，须提供。 */
    int intervalDays;
    /** 锚点日（服务端写=创建/编辑日），仅 INTERVAL 使用，须提供；候选=anchorDate + k×intervalDays。 */
    Instant anchorDate;
    /** 1=周一..7=周日（ISO-8601），仅 WEEKLY 使用，须非空。 */
    Set<Integer> weekdays;
    /** 隔周数（1..52），仅 WEEKLY 使用，须提供；>1 时另须 anchorDate（周相位锚，见 calculator）。 */
    int intervalWeeks;
    /** 1..31，仅 MONTHLY 使用，须非空；当月缺该日自然跳过。 */
    Set<Integer> monthDays;
    /** 仅 ONCE 使用，须非空。 */
    Instant onceAt;
    /** 有效期起点（含），null = 无下界。 */
    Instant planStartTime;
    /** 有效期终点（含），null = 无上界。 */
    Instant planEndTime;

    @Builder
    private static ScheduleSpec create(ScheduleType type, int hour, int minute,
                                       int intervalDays, Instant anchorDate,
                                       Set<Integer> weekdays, int intervalWeeks, Set<Integer> monthDays,
                                       Instant onceAt, Instant planStartTime, Instant planEndTime) {
        if (type == null) {
            throw new IllegalArgumentException("schedule type is required");
        }
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("hour must be 0-23: " + hour);
        }
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("minute must be 0-59: " + minute);
        }
        switch (type) {
            case WEEKLY:
                requireNonEmpty(weekdays, "weekdays", type);
                weekdays.forEach(d -> {
                    if (d < 1 || d > 7) {
                        throw new IllegalArgumentException("weekday must be 1-7: " + d);
                    }
                });
                // 隔周相位参数：值域 1-52；>1 必须有锚（周相位锚=含 anchorDate 的周），
                // =1 相位恒命中、锚不参与，允许无锚（存量无 intervalWeeks 键行由 ScheduleSpecs 归一为 1）
                if (intervalWeeks < 1 || intervalWeeks > 52) {
                    throw new IllegalArgumentException("intervalWeeks must be 1-52 for WEEKLY schedule: " + intervalWeeks);
                }
                if (intervalWeeks > 1 && anchorDate == null) {
                    throw new IllegalArgumentException(
                            "anchorDate is required for WEEKLY schedule with intervalWeeks > 1");
                }
                break;
            case MONTHLY:
                requireNonEmpty(monthDays, "monthDays", type);
                monthDays.forEach(d -> {
                    if (d < 1 || d > 31) {
                        throw new IllegalArgumentException("monthDay must be 1-31: " + d);
                    }
                });
                break;
            case ONCE:
                if (onceAt == null) {
                    throw new IllegalArgumentException("onceAt is required for ONCE schedule");
                }
                break;
            case INTERVAL:
                // 候选=anchorDate + k×intervalDays 当日 hour:minute；两参数由服务端装配恒写入，
                // 缺失即坏行——构造期拦截，计算函数内不重复判（与 WEEKLY/MONTHLY 必填集合同一惯例）
                if (intervalDays < 1 || intervalDays > 31) {
                    throw new IllegalArgumentException("intervalDays must be 1-31 for INTERVAL schedule: " + intervalDays);
                }
                if (anchorDate == null) {
                    throw new IllegalArgumentException("anchorDate is required for INTERVAL schedule");
                }
                break;
            case DAILY:
                break;
            default:
                throw new IllegalArgumentException("unknown schedule type: " + type);
        }
        // 防御性拷贝后封不可变：调用方持有原 Set 引用（如测试的 TreeSet）也不得在构造后篡改调度契约
        Set<Integer> immutableWeekdays = weekdays == null ? null : Collections.unmodifiableSet(new HashSet<>(weekdays));
        Set<Integer> immutableMonthDays = monthDays == null ? null : Collections.unmodifiableSet(new HashSet<>(monthDays));
        return new ScheduleSpec(type, hour, minute, intervalDays, anchorDate,
                immutableWeekdays, intervalWeeks, immutableMonthDays, onceAt, planStartTime, planEndTime);
    }

    private static void requireNonEmpty(Set<Integer> days, String field, ScheduleType type) {
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty for " + type + " schedule");
        }
    }
}
