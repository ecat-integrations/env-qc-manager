package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 调度时刻计算纯函数（无 IO、无时钟依赖、时区注入），质控调度器的唯一真相源。
 *
 * <p>应用场景：调度器 tick 与恢复（restart/编辑/重新启用后）都调用本函数，以当前时刻为
 * after 重算下次触发。「严格晚于 after」（同一分钟内已过触发点不再触发）是「错过不追补、
 * 从当前时刻续算」的机制保证。</p>
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li>DAILY/WEEKLY/MONTHLY 候选时刻秒恒为 00（墙钟 hour:minute）；ONCE 原样返回 onceAt。</li>
 *   <li>WEEKLY 隔周相位：周序差（锚周=含 anchorDate 的周，周一为一周之始）% intervalWeeks==0
 *       且 weekday∈weekdays；intervalWeeks=1 时相位恒命中（存量行无键语义）。</li>
 *   <li>INTERVAL 候选 = 锚点墙钟日 + k×intervalDays（k≥0）当日 hour:minute，逐日推进取
 *       严格晚于 after 的最小者；锚点由保存侧从 planStartTime 派生写入，编辑改期即重锚。</li>
 *   <li>有效期窗口闭区间：候选 &lt; planStart 跳过继续推进；候选 &gt; planEnd 后无命中返回 empty。</li>
 *   <li>MONTHLY 当月无选中日（如 31 日遇 9 月）自然跳过该月。</li>
 *   <li>未知/缺失 type 抛 {@link IllegalArgumentException}（严格模式，不猜默认）。</li>
 * </ul>
 *
 * @author coffee
 */
public final class ScheduleCalculator {

    private ScheduleCalculator() {
    }

    /**
     * 计算严格晚于 after 的下次触发时刻；秒恒 00（ONCE 原样）；受有效期窗口约束（闭区间）；
     * 无候选返回 empty。
     */
    public static Optional<Instant> nextFire(ScheduleSpec spec, Instant after, ZoneId zone) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (after == null || zone == null) {
            throw new IllegalArgumentException("after/zone must not be null");
        }
        switch (spec.getType()) {
            case ONCE:
                return nextFireOnce(spec, after);
            case DAILY:
                return nextFireByDay(spec, after, zone, cursor -> true, 62);
            case WEEKLY:
                // 隔周命中间隔至多 intervalWeeks 周：逐日推进上限按其推导（intervalWeeks*7+7 天
                // 覆盖最长对齐间隔+不满一周的相位偏移），52 周上界时 62 天硬上限会误判无命中
                return nextFireByDay(spec, after, zone, weeklyDayMatcher(spec, zone),
                        spec.getIntervalWeeks() * 7 + 7);
            case MONTHLY:
                return nextFireByDay(spec, after, zone,
                        cursor -> spec.getMonthDays().contains(cursor.getDayOfMonth()), 62);
            case INTERVAL:
                // 间隔≤31 → 距下一对齐日至多 30 天，62 天上限内必有命中或窗口外，不死循环
                return nextFireByDay(spec, after, zone, intervalDayMatcher(spec, zone), 62);
            default:
                throw new IllegalArgumentException("unknown schedule type: " + spec.getType());
        }
    }

    /**
     * WEEKLY 日匹配：weekday∈weekdays 且周相位对齐——周序差（锚周=含 anchorDate 的周，
     * 周一为一周之始，与 weekdays 编号对齐）% intervalWeeks==0。intervalWeeks=1 相位恒命中，
     * 锚不参与（存量无键行为不变）；锚周之前的候选不触发（周序差非负，与 INTERVAL 锚前不触发同一惯例）。
     */
    private static Predicate<ZonedDateTime> weeklyDayMatcher(ScheduleSpec spec, ZoneId zone) {
        if (spec.getIntervalWeeks() == 1) {
            return cursor -> spec.getWeekdays().contains(cursor.getDayOfWeek().getValue());
        }
        LocalDate anchorMonday = spec.getAnchorDate().atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return cursor -> {
            if (!spec.getWeekdays().contains(cursor.getDayOfWeek().getValue())) {
                return false;
            }
            long weekDiff = ChronoUnit.DAYS.between(anchorMonday,
                    cursor.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) / 7;
            return weekDiff >= 0 && weekDiff % spec.getIntervalWeeks() == 0;
        };
    }

    /**
     * INTERVAL 日匹配：候选日 = 锚点墙钟日 + k×intervalDays（k≥0）。锚点是保存时刻的
     * Instant，只取其 zone 墙钟日参与日序计算，时刻部分不掺入（服务端保存时写、编辑时重锚）。
     */
    private static Predicate<ZonedDateTime> intervalDayMatcher(ScheduleSpec spec, ZoneId zone) {
        LocalDate anchor = spec.getAnchorDate().atZone(zone).toLocalDate();
        return cursor -> {
            long diff = ChronoUnit.DAYS.between(anchor, cursor.toLocalDate());
            return diff >= 0 && diff % spec.getIntervalDays() == 0;
        };
    }

    private static Optional<Instant> nextFireOnce(ScheduleSpec spec, Instant after) {
        Instant onceAt = spec.getOnceAt();
        if (onceAt.isAfter(after) && withinWindow(spec, onceAt)) {
            return Optional.of(onceAt);
        }
        return Optional.empty();
    }

    /** 逐日推进（含当日）直到命中 dayMatcher 与窗口；MONTHLY 无 31 日月份靠逐日自然跳过。 */
    private static Optional<Instant> nextFireByDay(ScheduleSpec spec, Instant after, ZoneId zone,
                                                   Predicate<ZonedDateTime> dayMatcher, int maxDays) {
        // 同分钟不触发：从 after 的下一分钟起点开始逐日推进
        ZonedDateTime cursor = after.atZone(zone).truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(1);
        // 上限防死循环：DAILY/MONTHLY/INTERVAL 用 62 天（任何合法日程两月内必有命中或窗口外）；
        // WEEKLY 按 intervalWeeks 推导（见调用处），越界仍未命中按窗口外处理
        for (int i = 0; i < maxDays; i++) {
            ZonedDateTime candidate = cursor.toLocalDate()
                    .atTime(spec.getHour(), spec.getMinute()).atZone(zone);
            if (candidate.toInstant().isAfter(after)
                    && dayMatcher.test(candidate)
                    && withinWindow(spec, candidate.toInstant())) {
                return Optional.of(candidate.toInstant());
            }
            if (spec.getPlanEndTime() != null
                    && candidate.toInstant().isAfter(spec.getPlanEndTime())) {
                // 已越过窗口终点，后续候选只会更晚
                return Optional.empty();
            }
            cursor = cursor.plusDays(1);
        }
        return Optional.empty();
    }

    private static boolean withinWindow(ScheduleSpec spec, Instant t) {
        return (spec.getPlanStartTime() == null || !t.isBefore(spec.getPlanStartTime()))
                && (spec.getPlanEndTime() == null || !t.isAfter(spec.getPlanEndTime()));
    }
}
