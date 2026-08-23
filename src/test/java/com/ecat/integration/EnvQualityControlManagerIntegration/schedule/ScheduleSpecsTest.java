package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * schedule_config JSON → ScheduleSpec 解析边界（严格模式：坏输入抛 IAE 不猜默认）。
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
}
