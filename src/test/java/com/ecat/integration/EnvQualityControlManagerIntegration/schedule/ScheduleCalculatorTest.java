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
                .intervalWeeks(1).weekdays(days(1, 3)).build();
        Optional<Instant> r = ScheduleCalculator.nextFire(spec, AFTER, ZONE);
        assertEquals(wall(2026, 8, 24, 2, 0), r.get());
    }

    @Test
    void weekly_sameDayBeforeTime_returnsToday() {
        // 2026-08-24 是周一，after=周一 01:00 → 当日 02:00
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                .intervalWeeks(1).weekdays(days(1, 3)).build();
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
                .intervalWeeks(1).weekdays(days(1)).build();
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
            .type(ScheduleType.WEEKLY).hour(2).minute(0).intervalWeeks(1).weekdays(source).build();
        source.add(5);
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(1, 3)), spec.getWeekdays());
        assertThrows(UnsupportedOperationException.class, () -> spec.getWeekdays().add(7));
    }

    // ---------- INTERVAL（03 设计 §7.1：候选=锚点墙钟日+k×intervalDays 当日 hour:minute，62 天上限沿用） ----------

    /** INTERVAL spec：锚点带保存时刻 15:23，验证锚点只取墙钟日、其时刻不参与日序计算。 */
    private static ScheduleSpec interval(int intervalDays, int anchorYear, int anchorMonth, int anchorDay,
                                         int hour, int minute) {
        return ScheduleSpec.builder().type(ScheduleType.INTERVAL).hour(hour).minute(minute)
                .intervalDays(intervalDays)
                .anchorDate(at(anchorYear, anchorMonth, anchorDay, 15, 23, 0))
                .build();
    }

    @Test
    void interval_anchorDayBeforeTime_firesAnchorDay() {
        // 锚点日 08-21、间隔 2、02:45：after=01:00 未过当日触发点 → 当日（k=0）命中
        assertEquals(wall(2026, 8, 21, 2, 45),
                ScheduleCalculator.nextFire(interval(2, 2026, 8, 21, 2, 45),
                        at(2026, 8, 21, 1, 0, 0), ZONE).get());
    }

    @Test
    void interval_afterAnchorDayTime_advancesToNextMultiple() {
        // after=锚点日 09:30:15 已过当日 02:45 → 下一命中=锚点+2 天
        assertEquals(wall(2026, 8, 23, 2, 45),
                ScheduleCalculator.nextFire(interval(2, 2026, 8, 21, 2, 45), AFTER, ZONE).get());
        // 同分钟不触发：after=08-21 02:45:30 → 仍推进 08-23
        assertEquals(wall(2026, 8, 23, 2, 45),
                ScheduleCalculator.nextFire(interval(2, 2026, 8, 21, 2, 45),
                        at(2026, 8, 21, 2, 45, 30), ZONE).get());
    }

    @Test
    void interval_crossMonth_seriesContinues() {
        // 锚点 08-21、间隔 11：对齐日 08-21、09-01、09-12、09-23、10-04…；after=09-30 → 10-04（跨月正确）
        assertEquals(wall(2026, 10, 4, 2, 45),
                ScheduleCalculator.nextFire(interval(11, 2026, 8, 21, 2, 45),
                        at(2026, 9, 30, 12, 0, 0), ZONE).get());
    }

    @Test
    void interval_maxInterval31_nextWithin62DayCap() {
        // 间隔上界 31：锚点 08-01、after=08-02 → 下一对齐日 09-01（推进 30 天 < 62 天上限，不死循环）
        assertEquals(wall(2026, 9, 1, 2, 45),
                ScheduleCalculator.nextFire(interval(31, 2026, 8, 1, 2, 45),
                        at(2026, 8, 2, 0, 0, 0), ZONE).get());
    }

    @Test
    void interval_windowNotYetStarted_returnsFirstAlignedInWindow() {
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.INTERVAL).hour(2).minute(45)
                .intervalDays(2).anchorDate(at(2026, 8, 21, 15, 23, 0))
                .planStartTime(wall(2026, 8, 22, 0, 0)).build();
        // 对齐日 08-21 候选早于窗口起 → 跳过；08-23 为窗口内首个命中
        assertEquals(wall(2026, 8, 23, 2, 45),
                ScheduleCalculator.nextFire(spec, at(2026, 8, 21, 1, 0, 0), ZONE).get());
    }

    @Test
    void interval_windowExpired_returnsEmpty() {
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.INTERVAL).hour(2).minute(45)
                .intervalDays(2).anchorDate(at(2026, 8, 21, 15, 23, 0))
                .planEndTime(wall(2026, 8, 22, 0, 0)).build();
        assertFalse(ScheduleCalculator.nextFire(spec, AFTER, ZONE).isPresent());
    }

    @Test
    void interval_windowEndInclusive_fires() {
        // 闭区间：下一对齐候选恰等于 planEnd → 触发
        ScheduleSpec spec = ScheduleSpec.builder().type(ScheduleType.INTERVAL).hour(2).minute(45)
                .intervalDays(2).anchorDate(at(2026, 8, 21, 15, 23, 0))
                .planEndTime(wall(2026, 8, 23, 2, 45)).build();
        assertEquals(wall(2026, 8, 23, 2, 45),
                ScheduleCalculator.nextFire(spec, AFTER, ZONE).get());
    }

    @Test
    void interval_editReanchor_shiftsSeries() {
        // E3 编辑重锚：同 after、同间隔，锚点从 08-21 改为编辑日 09-10 → 对齐序列随之平移
        assertEquals(wall(2026, 9, 11, 2, 45),
                ScheduleCalculator.nextFire(interval(3, 2026, 8, 21, 2, 45),
                        at(2026, 9, 10, 12, 0, 0), ZONE).get());
        assertEquals(wall(2026, 9, 13, 2, 45),
                ScheduleCalculator.nextFire(interval(3, 2026, 9, 10, 2, 45),
                        at(2026, 9, 10, 12, 0, 0), ZONE).get());
    }

    // ---------- WEEKLY intervalWeeks（统一调度：隔周相位，锚=含 anchorDate 的周，周一为一周之始） ----------
    // 日历锚定：2026-09-21 周一、09-23 周三、09-24 周四、10-05 周一、10-07 周三、2027-01-04 周一。

    /** 带 anchorDate 的 WEEKLY spec（锚点时刻任意，只取墙钟日所在的周；时刻不参与周序计算）。 */
    private static ScheduleSpec weekly(int intervalWeeks, int anchorYear, int anchorMonth, int anchorDay,
                                       Integer... weekdays) {
        return ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                .intervalWeeks(intervalWeeks).weekdays(days(weekdays))
                .anchorDate(at(anchorYear, anchorMonth, anchorDay, 10, 0, 0)).build();
    }

    @Test
    void weekly_everySecondWeek_hitsOnlyPhaseAlignedWeeks() {
        // 锚 09-24（周四，锚周周一=09-21），周三隔周：09-23 命中但已过 → 09-30 属第 1 周不中 → 10-07 第 2 周命中
        assertEquals(wall(2026, 10, 7, 2, 0),
                ScheduleCalculator.nextFire(weekly(2, 2026, 9, 24, 3),
                        at(2026, 9, 23, 9, 0, 0), ZONE).get());
    }

    @Test
    void weekly_phaseAnchorsToAnchorWeek_notAnchorDay() {
        // 锚是周中的 09-24（周四）：相位=09-21 起的整周，锚周内的周三 09-23 即命中——
        // 若误解为「锚日+k×14 天」则会跳到 10-08，本用例锁周相位语义
        assertEquals(wall(2026, 9, 23, 2, 0),
                ScheduleCalculator.nextFire(weekly(2, 2026, 9, 24, 3),
                        at(2026, 9, 22, 12, 0, 0), ZONE).get());
    }

    @Test
    void weekly_intervalWeeks1_matchesEverySelectedWeek() {
        // intervalWeeks=1 显式给值与存量每周行为完全一致（周五 after → 下周一）
        assertEquals(wall(2026, 8, 24, 2, 0),
                ScheduleCalculator.nextFire(weekly(1, 2026, 8, 20, 1, 3), AFTER, ZONE).get());
    }

    @Test
    void weekly_weeksBeforeAnchor_neverFire() {
        // 锚 10-08（锚周周一=10-05）：负周序差即使模整除也不命中（09-21 周差-2、%2==0），
        // 首个命中=锚周内周三 10-07——与 INTERVAL「锚点日之前不触发」同一惯例
        assertEquals(wall(2026, 10, 7, 2, 0),
                ScheduleCalculator.nextFire(weekly(2, 2026, 10, 8, 3),
                        at(2026, 9, 22, 12, 0, 0), ZONE).get());
    }

    @Test
    void weekly_maxIntervalWeeks52_hitWithinDerivedCap() {
        // 隔 52 周：after 09-23 → 锚周 2026-01-05 的第 52 周差=2027-01-04 周，命中 2027-01-06 周三
        // （推进约 105 天：62 天旧上限会误返回 empty，本用例锁 intervalWeeks*7+7 推导上限）
        assertEquals(wall(2027, 1, 6, 2, 0),
                ScheduleCalculator.nextFire(weekly(52, 2026, 1, 8, 3),
                        at(2026, 9, 23, 9, 0, 0), ZONE).get());
    }

    @Test
    void weeklySpec_intervalWeeksConstructionGuards() {
        // intervalWeeks 是 WEEKLY 必填（0=未设），值域 1-52；隔周>1 须 anchorDate（周相位锚）
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                        .weekdays(days(3)).build());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                        .intervalWeeks(0).weekdays(days(3)).build());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                        .intervalWeeks(53).weekdays(days(3)).build());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.builder().type(ScheduleType.WEEKLY).hour(2).minute(0)
                        .intervalWeeks(2).weekdays(days(3)).build());
    }
}
