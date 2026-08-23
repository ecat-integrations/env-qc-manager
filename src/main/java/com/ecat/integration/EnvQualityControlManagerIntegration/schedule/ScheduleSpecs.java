package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * schedule_config JSON → {@link ScheduleSpec} 解析 helper（04 §2 结构：
 * {hour, minute, weekdays[], monthDays[], onceMode, onceAt}，另可内嵌 planStart/planEnd 覆盖空列值）。
 *
 * <p>应用场景：调度器每轮 fire/misfire 后重算 next_fire_time 时，把 qcm_plan 行的
 * schedule_config 字符串解析回 spec 交给 {@link ScheduleCalculator}。解析失败抛
 * {@link IllegalArgumentException}（严格模式，不猜默认值）。</p>
 */
public final class ScheduleSpecs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduleSpecs() {
    }

    /**
     * 解析计划行为调度值对象。
     *
     * @param scheduleType 计划行 schedule_type 列（DAILY/WEEKLY/MONTHLY/ONCE，config 内不含 type）
     * @param scheduleConfigJson schedule_config 列原文（jsonb 转字符串）
     * @param planStartFallback 计划行 plan_start_time 列；config 内嵌 planStart 优先
     * @param planEndFallback 计划行 plan_end_time 列；config 内嵌 planEnd 优先
     */
    public static ScheduleSpec fromConfig(String scheduleType, String scheduleConfigJson,
                                          Instant planStartFallback, Instant planEndFallback) {
        if (scheduleType == null || scheduleType.isEmpty()) {
            throw new IllegalArgumentException("schedule_type is required to build ScheduleSpec");
        }
        JsonNode config = parse(scheduleConfigJson);
        ScheduleType type = parseType(scheduleType);
        try {
            return ScheduleSpec.builder()
                    .type(type)
                    .hour(intField(config, "hour", 0))
                    .minute(intField(config, "minute", 0))
                    .weekdays(intSet(config, "weekdays"))
                    .monthDays(intSet(config, "monthDays"))
                    .onceAt(instantField(config, "onceAt"))
                    .planStartTime(firstNonNull(instantField(config, "planStart"), planStartFallback))
                    .planEndTime(firstNonNull(instantField(config, "planEnd"), planEndFallback))
                    .build();
        } catch (IllegalArgumentException e) {
            // 包装带上类型与原文片段，定位坏行不丢上下文（非法值校验抛自 ScheduleSpec 构造期）
            throw new IllegalArgumentException("invalid schedule_config for " + scheduleType
                    + ": " + scheduleConfigJson + " (" + e.getMessage() + ")", e);
        }
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException("schedule_config is required to build ScheduleSpec");
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("schedule_config is not valid JSON: " + json, e);
        }
    }

    private static ScheduleType parseType(String scheduleType) {
        try {
            return ScheduleType.valueOf(scheduleType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown schedule_type: " + scheduleType, e);
        }
    }

    private static int intField(JsonNode config, String field, int defaultValue) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isInt()) {
            throw new IllegalArgumentException(field + " must be an integer: " + node);
        }
        return node.asInt();
    }

    private static Set<Integer> intSet(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull() || !node.isArray()) {
            return null;
        }
        Set<Integer> values = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isInt()) {
                throw new IllegalArgumentException(field + " must contain integers only: " + item);
            }
            values.add(item.asInt());
        }
        return values;
    }

    private static Instant instantField(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 string: " + node);
        }
        try {
            return Instant.parse(node.asText());
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " is not ISO-8601: " + node.asText(), e);
        }
    }

    private static Instant firstNonNull(Instant a, Instant b) {
        return a != null ? a : b;
    }
}
