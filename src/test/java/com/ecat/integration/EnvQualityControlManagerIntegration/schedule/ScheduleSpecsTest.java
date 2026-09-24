package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * schedule_config JSON → ScheduleSpec 解析边界（严格模式：坏输入抛 IAE 不猜默认）。
 *
 * @author coffee
 */
class ScheduleSpecsTest {

    private static final Instant PLAN_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant PLAN_END = Instant.parse("2026-12-31T00:00:00Z");

    @Test
    void dailyConfig_buildsSpecWithColumnWindow() {
        ScheduleSpec spec = ScheduleSpecs.fromConfig("DAILY", "{\"hour\":10,\"minute\":30}",
                PLAN_START, PLAN_END);
        assertEquals(ScheduleType.DAILY, spec.getType());
        assertEquals(10, spec.getHour());
        assertEquals(30, spec.getMinute());
        assertEquals(PLAN_START, spec.getPlanStartTime());
        assertEquals(PLAN_END, spec.getPlanEndTime());
    }

    @Test
    void weeklyConfig_parsesDaySet() {
        ScheduleSpec spec = ScheduleSpecs.fromConfig("WEEKLY",
                "{\"hour\":9,\"minute\":0,\"weekdays\":[1,3,5]}", null, null);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(1, 3, 5)), spec.getWeekdays());
    }

    @Test
    void onceConfig_configWindowOverridesNullColumns() {
        // config 内嵌 planStart/planEnd 优先于空的行级列（D18 列可空）
        ScheduleSpec spec = ScheduleSpecs.fromConfig("ONCE",
                "{\"onceAt\":\"2026-08-21T02:00:00Z\",\"planStart\":\"2026-08-01T00:00:00Z\"}",
                null, null);
        assertEquals(Instant.parse("2026-08-21T02:00:00Z"), spec.getOnceAt());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), spec.getPlanStartTime());
        assertEquals(null, spec.getPlanEndTime());
    }

    @Test
    void invalidJson_throwsIaeWithContext() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("DAILY", "{bad json", null, null));
        assertEquals(true, e.getMessage().contains("not valid JSON"));
    }

    @Test
    void missingConfig_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("DAILY", null, null, null));
    }

    @Test
    void unknownTypeOrIllegalField_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("CRON", "{\"hour\":10}", null, null));
        // hour 越界由 ScheduleSpec 构造期拦截，解析层包上下文重抛
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("DAILY", "{\"hour\":25,\"minute\":0}", null, null));
    }

    // ---------- INTERVAL（03 设计 §7.1：intervalDays + 服务端写锚点 anchorDate） ----------

    @Test
    void intervalConfig_parsesDaysAndAnchor() {
        ScheduleSpec spec = ScheduleSpecs.fromConfig("INTERVAL",
                "{\"intervalDays\":2,\"hour\":2,\"minute\":45,\"anchorDate\":\"2026-08-21T00:00:00Z\"}",
                null, null);
        assertEquals(ScheduleType.INTERVAL, spec.getType());
        assertEquals(2, spec.getIntervalDays());
        assertEquals(Instant.parse("2026-08-21T00:00:00Z"), spec.getAnchorDate());
        assertEquals(2, spec.getHour());
        assertEquals(45, spec.getMinute());
    }

    @Test
    void intervalConfig_missingAnchorDateThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("INTERVAL",
                        "{\"intervalDays\":2,\"hour\":2,\"minute\":45}", null, null));
        assertEquals(true, e.getMessage().contains("anchorDate is required"));
    }

    @Test
    void intervalConfig_missingIntervalDaysThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("INTERVAL",
                        "{\"hour\":2,\"minute\":45,\"anchorDate\":\"2026-08-21T00:00:00Z\"}", null, null));
        assertEquals(true, e.getMessage().contains("intervalDays must be 1-31"));
    }

    @Test
    void intervalConfig_intervalDaysOutOfRangeThrows() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("INTERVAL",
                        "{\"intervalDays\":0,\"hour\":2,\"minute\":45,\"anchorDate\":\"2026-08-21T00:00:00Z\"}",
                        null, null));
        assertEquals(true, zero.getMessage().contains("intervalDays must be 1-31"));
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("INTERVAL",
                        "{\"intervalDays\":32,\"hour\":2,\"minute\":45,\"anchorDate\":\"2026-08-21T00:00:00Z\"}",
                        null, null));
        assertEquals(true, over.getMessage().contains("intervalDays must be 1-31"));
    }

    // ---------- WEEKLY intervalWeeks（统一调度；存量无键=1 是唯一解析兼容点） ----------

    @Test
    void weeklyConfig_missingIntervalWeeksKey_parsesAsEveryWeek() {
        // 存量 WEEKLY 行 config 无 intervalWeeks 键（统一调度模型上线前保存的行全是每 1 周语义）
        ScheduleSpec spec = ScheduleSpecs.fromConfig("WEEKLY",
                "{\"hour\":9,\"minute\":0,\"weekdays\":[1,3]}", null, null);
        assertEquals(1, spec.getIntervalWeeks());
    }

    @Test
    void weeklyConfig_intervalWeeksAndAnchorParsed() {
        ScheduleSpec spec = ScheduleSpecs.fromConfig("WEEKLY",
                "{\"hour\":9,\"minute\":0,\"weekdays\":[3],\"intervalWeeks\":2,"
                        + "\"anchorDate\":\"2026-09-24T01:00:00Z\"}", null, null);
        assertEquals(2, spec.getIntervalWeeks());
        assertEquals(Instant.parse("2026-09-24T01:00:00Z"), spec.getAnchorDate());
    }

    @Test
    void weeklyConfig_intervalWeeksOutOfRangeThrows() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("WEEKLY",
                        "{\"hour\":9,\"minute\":0,\"weekdays\":[3],\"intervalWeeks\":0}", null, null));
        assertEquals(true, zero.getMessage().contains("intervalWeeks must be 1-52"));
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("WEEKLY",
                        "{\"hour\":9,\"minute\":0,\"weekdays\":[3],\"intervalWeeks\":53}", null, null));
        assertEquals(true, over.getMessage().contains("intervalWeeks must be 1-52"));
    }

    @Test
    void weeklyConfig_intervalWeeksOverOneMissingAnchorThrows() {
        // 隔周>1 的周相位锚无来源=坏行，构造期拦截（=1 无键/无锚都合法）
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpecs.fromConfig("WEEKLY",
                        "{\"hour\":9,\"minute\":0,\"weekdays\":[3],\"intervalWeeks\":2}", null, null));
        assertEquals(true, e.getMessage().contains("anchorDate is required"));
    }
}
