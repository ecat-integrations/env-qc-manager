package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 调度纯函数边界用例验收（AC-A2）：时区注入 Asia/Shanghai，无 IO 无 sleep。
 * 基准 after = 2026-08-21T09:30:15+08:00（含秒，验证同分钟不触发与秒归零）。
 *
 * @author coffee
 */
class ScheduleCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 上海墙钟时刻 → Instant，避免魔法串散落。 */
    private static Instant at(int year, int month, int day, int hour, int minute, int second) {
        return ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZONE).toInstant();
    }

    private static Instant wall(int year, int month, int day, int hour, int minute) {
        return at(year, month, day, hour, minute, 0);
    }

    private static ScheduleSpec.ScheduleSpecBuilder daily(int hour, int minute) {
        return ScheduleSpec.builder().type(ScheduleType.DAILY).hour(hour).minute(minute);
    }

    private static Set<Integer> days(Integer... values) {
        return new TreeSet<>(Arrays.asList(values));
    }

    private static final Instant AFTER = at(2026, 8, 21, 9, 30, 15);

    @Test
    void daily_sameMinute_returnsTomorrow() {
        // after 09:30:15 已过当日 09:30:00 候选（严格晚于），推进到次日
        Optional<Instant> r = ScheduleCalculator.nextFire(daily(9, 30).build(), AFTER, ZONE);
        assertEquals(wall(2026, 8, 22, 9, 30), r.get());
    }

    @Test
    void daily_beforeTarget_returnsToday() {
        Optional<Instant> r = ScheduleCalculator.nextFire(daily(10, 0).build(), AFTER, ZONE);
        assertEquals(wall(2026, 8, 21, 10, 0), r.get());
    }

    @Test
    void weekly_nextSelectedDay() {
        // 2026-08-21 是周五；周一=1 周三=3；下周一 = 2026-08-24
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                .weekdays(days(1, 3)).build();
        Optional<Instant> r = ScheduleCalculator.nextFire(spec, AFTER, ZONE);
        assertEquals(wall(2026, 8, 24, 2, 0), r.get());
    }

    @Test
    void weekly_sameDayBeforeTime_returnsToday() {
        // 2026-08-24 是周一，after=周一 01:00 → 当日 02:00
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                .weekdays(days(1, 3)).build();
        Optional<Instant> r = ScheduleCalculator.nextFire(spec, at(2026, 8, 24, 1, 0, 0), ZONE);
        assertEquals(wall(2026, 8, 24, 2, 0), r.get());
    }

    @Test
    void monthly_missingDaySkipsMonth() {
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.MONTHLY).hour(2).minute(0)
                .monthDays(days(31)).build();
        // after=08-21 → 08-31 存在
        assertEquals(wall(2026, 8, 31, 2, 0),
                ScheduleCalculator.nextFire(spec, AFTER, ZONE).get());
        // after=09-01，9 月无 31 → 跳到 10-31
        assertEquals(wall(2026, 10, 31, 2, 0),
                ScheduleCalculator.nextFire(spec, wall(2026, 9, 1, 0, 0), ZONE).get());
    }

    @Test
    void monthly_multiDaysAscending() {
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.MONTHLY).hour(2).minute(0)
                .monthDays(days(1, 15)).build();
        Optional<Instant> r = ScheduleCalculator.nextFire(spec, wall(2026, 8, 5, 0, 0), ZONE);
        assertEquals(wall(2026, 8, 15, 2, 0), r.get());
    }

    @Test
    void once_future_returnsOnceAt() {
        Instant onceAt = at(2026, 8, 21, 9, 30, 45);
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.ONCE).onceAt(onceAt).build();
        // ONCE 原样返回：不归零秒，含秒时刻
        assertEquals(onceAt, ScheduleCalculator.nextFire(spec, AFTER, ZONE).get());
    }

    @Test
    void once_past_returnsEmpty() {
        Instant onceAt = wall(2026, 8, 21, 8, 0);
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.ONCE).onceAt(onceAt).build();
        assertFalse(ScheduleCalculator.nextFire(spec, AFTER, ZONE).isPresent());
    }

    @Test
    void resumeDoesNotBackfill() {
        // after=周三 2026-08-26，WEEKLY 周一 → 下周一 2026-08-31，错过的不补
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                .weekdays(days(1)).build();
        Optional<Instant> r = ScheduleCalculator.nextFire(spec, wall(2026, 8, 26, 12, 0), ZONE);
        assertEquals(wall(2026, 8, 31, 2, 0), r.get());
    }

    @Test
    void window_notYetStarted_returnsFirstInWindow() {
        ScheduleSpec spec = daily(2, 0)
                .planStartTime(wall(2026, 8, 25, 0, 0)).build();
        Optional<Instant> r = ScheduleCalculator.nextFire(spec, AFTER, ZONE);
        assertEquals(wall(2026, 8, 25, 2, 0), r.get());
    }

    @Test
    void window_expired_returnsEmpty() {
        ScheduleSpec spec = daily(2, 0)
                .planEndTime(wall(2026, 8, 21, 1, 0)).build();
        assertFalse(ScheduleCalculator.nextFire(spec, AFTER, ZONE).isPresent());
    }

    @Test
    void window_endInclusive_fires() {
        // 09:30:15 之后的下一个 DAILY 10:00 候选 == planEnd 10:00 → 触发（闭区间）
        ScheduleSpec spec = daily(10, 0)
                .planEndTime(wall(2026, 8, 21, 10, 0)).build();
        assertEquals(wall(2026, 8, 21, 10, 0),
                ScheduleCalculator.nextFire(spec, AFTER, ZONE).get());
    }

    @Test
    void secondsAlwaysZero_exceptOnce() {
        // after 含秒 09:30:15：同分钟候选被严格晚于排除，次日候选秒归零
        assertEquals(wall(2026, 8, 22, 9, 30),
                ScheduleCalculator.nextFire(daily(9, 30).build(), AFTER, ZONE).get());
        // MONTHLY 候选同样秒归零
        ScheduleSpec m = ScheduleSpec.builder().type(ScheduleType.MONTHLY).hour(2).minute(0)
                .monthDays(days(1)).build();
        assertEquals(wall(2026, 9, 1, 2, 0),
                ScheduleCalculator.nextFire(m, AFTER, ZONE).get());
    }

    @Test
    void constructor_invalidFields_throwIAE() {
        assertThrows(IllegalArgumentException.class, () -> daily(24, 0).build());
        assertThrows(IllegalArgumentException.class, () -> daily(0, 60).build());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                        .weekdays(days(0)).build());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().type(ScheduleType.MONTHLY).hour(2).minute(0)
                        .monthDays(days(32)).build());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().type(ScheduleType.MONTHLY).hour(2).minute(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().type(ScheduleType.ONCE).build());
    }

    @Test
    void unknownType_throwsIAE() {
        // 枚举闭集下「未知 type」不可构造，等价拦截点 = type 缺失（null），构造期即抛
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().hour(0).minute(0).build());
    }

    /** LocalDate 语义自检：测试假设的星期与日历一致（2026-08-21 是周五）。 */
    @Test
    void calendarAssumptionsHold() {
        assertEquals(5, LocalDate.of(2026, 8, 21).getDayOfWeek().getValue());
        assertEquals(1, LocalDate.of(2026, 8, 24).getDayOfWeek().getValue());
    }

    @Test
    void specIsImmutableAgainstSourceSetMutation() {
        // 评审缺陷回归锁：构造后篡改源 Set 不得影响调度契约，getter 返回不可变视图
        Set<Integer> source = new java.util.TreeSet<>(java.util.Arrays.asList(1, 3));
        ScheduleSpec spec = ScheduleSpec.builder()
            .type(ScheduleType.WEEKLY).hour(2).minute(0).weekdays(source).build();
        source.add(5);
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(1, 3)), spec.getWeekdays());
        assertThrows(UnsupportedOperationException.class, () -> spec.getWeekdays().add(7));
    }
}
