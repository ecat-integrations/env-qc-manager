package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * qcm_plan 行 → {@link QcExecutionRequest} 的唯一装配路径：SCHEDULED（ScheduledPlanFireAction）
 * 与 MANUAL（service runNow）两源共用，防止两处各写一份 JSON 解析逻辑漂移。
 *
 * @author coffee
 */
public final class PlanRequestAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanRequestAssembler() {
    }

    /**
     * 装配执行请求（仪器/浓度/流量/时长覆盖/量程百分比/快照全从 plan 列读取）。
     */
    public static QcExecutionRequest assemble(QcmPlan plan) {
        return QcExecutionRequest.builder()
                .planId(plan.getId())
                .qcType(plan.getQcType())
                .instruments(parseInstruments(plan))
                .concentrationPpb(plan.getConcentrationPpb())
                .pointPercents(parsePointPercents(plan.getPointPercents()))
                .flowRateLpm(plan.getFlowRateLpm())
                .durationOverrides(parseDurationOverrides(plan.getDurationOverrides()))
                .planSnapshotJson(buildSnapshotJson(plan))
                .build();
    }

    /** instruments JSON 数组（["SO2","NO2"]）→ List；解析失败抛 IllegalArgumentException（严格模式，坏行可见）。 */
    static List<String> parseInstruments(QcmPlan plan) {
        List<String> instruments = new ArrayList<>();
        JsonNode node = parseJson(plan.getInstruments(), "instruments");
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("qcm 计划 " + plan.getId() + " instruments 非法: " + plan.getInstruments());
        }
        for (JsonNode item : node) {
            instruments.add(item.asText());
        }
        return instruments;
    }

    /** durationOverrides 稀疏 JSON → Map（数值保持 Number 语义透传 composer flowParams）；空/缺省返回 null。 */
    static Map<String, Object> parseDurationOverrides(String durationOverridesJson) {
        JsonNode node = parseJson(durationOverridesJson, "duration_overrides");
        if (node == null || !node.isObject() || node.size() == 0) {
            return null;
        }
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isInt()) {
                overrides.put(e.getKey(), v.asInt());
            } else if (v.isLong()) {
                overrides.put(e.getKey(), v.asLong());
            } else if (v.isFloatingPointNumber()) {
                overrides.put(e.getKey(), (float) v.asDouble());
            } else if (v.isTextual()) {
                overrides.put(e.getKey(), v.asText());
            } else {
                throw new IllegalArgumentException("duration_overrides 含不支持的值类型: " + e.getKey());
            }
        }
        return overrides;
    }

    /** pointPercents JSON（0~1 小数序列）→ List&lt;Float&gt;；NULL=走 composer 默认序列。 */
    static List<Float> parsePointPercents(String pointPercentsJson) {
        JsonNode node = parseJson(pointPercentsJson, "point_percents");
        if (node == null) {
            return null;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("point_percents 非法: " + pointPercentsJson);
        }
        List<Float> percents = new ArrayList<>();
        for (JsonNode item : node) {
            percents.add((float) item.asDouble());
        }
        return percents;
    }

    /** 触发时计划配置快照（名称/类型/仪器/参数摘要，FR-04-09 计划删除后溯源）。 */
    static String buildSnapshotJson(QcmPlan plan) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("planName", plan.getPlanName());
        snapshot.put("qcType", plan.getQcType());
        ArrayNode instruments = snapshot.putArray("instruments");
        for (String instrument : parseInstruments(plan)) {
            instruments.add(instrument);
        }
        BigDecimal conc = plan.getConcentrationPpb();
        if (conc != null) {
            snapshot.put("concentrationPpb", conc);
        }
        BigDecimal flow = plan.getFlowRateLpm();
        if (flow != null) {
            snapshot.put("flowRateLpm", flow);
        }
        return snapshot.toString();
    }

    private static JsonNode parseJson(String json, String field) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " is not valid JSON: " + json, e);
        }
    }
}
