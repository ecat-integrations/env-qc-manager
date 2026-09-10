package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

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
                return nextFireByDay(spec, after, zone, cursor -> true);
            case WEEKLY:
                return nextFireByDay(spec, after, zone,
                        cursor -> spec.getWeekdays().contains(cursor.getDayOfWeek().getValue()));
            case MONTHLY:
                return nextFireByDay(spec, after, zone,
                        cursor -> spec.getMonthDays().contains(cursor.getDayOfMonth()));
            default:
                throw new IllegalArgumentException("unknown schedule type: " + spec.getType());
        }
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
                                                   java.util.function.Predicate<ZonedDateTime> dayMatcher) {
        // 同分钟不触发：从 after 的下一分钟起点开始逐日推进
        ZonedDateTime cursor = after.atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .plusMinutes(1);
        // 上限 62 天（最长两个月）防死循环：任何合法月日月程两月内必有命中或窗口外
        for (int i = 0; i < 62; i++) {
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
