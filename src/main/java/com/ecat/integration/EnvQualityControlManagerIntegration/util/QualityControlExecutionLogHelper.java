package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseExecutionRecord;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

/**
 * 质控执行日志 JSON：统一 {@code params}、{@code result}（指标体）、{@code statusMap}（执行器状态），
 * 并与历史「扁平」结构兼容解析。
 */
public final class QualityControlExecutionLogHelper {

    /**
     * 编排器（或任务层）写入的质控各相位起止时间；报告侧用其中「读数/采样」相位作为关键参数时间窗。
     * <p>元素示例：{@code {"phaseCode":"READ_SAMPLES","phaseName":"读数","startTimeMillis":...,"endTimeMillis":...}}
     * 或 {@code startTime}/{@code endTime} 为 {@code "yyyy-MM-dd HH:mm:ss"} 字符串。
     */
    public static final String QC_PHASE_TIMELINES_KEY = "qcPhaseTimelines";

    private static final String[] TIME_PATTERNS = {
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    };

    private QualityControlExecutionLogHelper() {
    }

    public static Map<String, Object> buildStatusMap(ExecutorResultBase result) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (result == null) {
            m.put("isPass", false);
            m.put("isException", false);
            m.put("resultMessage", "");
            m.put("errorMessage", "");
            return m;
        }
        m.put("isPass", result.isPass());
        m.put("isException", result.isException());
        m.put("resultMessage", result.getResultMessage() != null ? result.getResultMessage() : "");
        m.put("errorMessage", result.getErrorMessage() != null ? result.getErrorMessage() : "");
        return m;
    }

    /**
     * 组装入库的 execution_log JSON 字符串（无关键参数快照时传 {@code null}）。
     */
    public static String toExecutionLogJson(
            Map<String, Object> params,
            Object resultBody,
            ExecutorResultBase result) {
        return toExecutionLogJson(params, resultBody, result, null);
    }

    /**
     * 组装入库的 execution_log JSON 字符串（可选根级 {@code keyParametersSnapshot}）。
     */
    public static String toExecutionLogJson(
            Map<String, Object> params,
            Object resultBody,
            ExecutorResultBase result,
            List<Map<String, Object>> keyParametersSnapshot) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<?> phaseTimelines = null;
        if (params != null && !params.isEmpty()) {
            Map<String, Object> paramsBody = new LinkedHashMap<>(params);
            Object pt = paramsBody.remove(QC_PHASE_TIMELINES_KEY);
            if (pt instanceof List && !((List<?>) pt).isEmpty()) {
                phaseTimelines = (List<?>) pt;
            }
            if (!paramsBody.isEmpty()) {
                root.put("params", paramsBody);
            }
        }
        if ((phaseTimelines == null || phaseTimelines.isEmpty()) && result != null) {
            List<Map<String, Object>> fromComposer = qcPhaseTimelinesFromPhaseRecords(result.getPhaseRecords());
            if (!fromComposer.isEmpty()) {
                phaseTimelines = fromComposer;
            }
        }
        if (phaseTimelines != null && !phaseTimelines.isEmpty()) {
            root.put(QC_PHASE_TIMELINES_KEY, new ArrayList<>(phaseTimelines));
        }
        if (resultBody == null) {
            root.put("result", Collections.emptyMap());
        } else {
            root.put("result", resultBody);
        }
        root.put("statusMap", buildStatusMap(result));
        if (keyParametersSnapshot != null && !keyParametersSnapshot.isEmpty()) {
            root.put("keyParametersSnapshot", keyParametersSnapshot);
        }
        Date[] samplingWin = resolveReadPhaseWindowFromTimelinesList(phaseTimelines);
        if (samplingWin != null) {
            Map<String, Object> win = new LinkedHashMap<>();
            win.put("startTimeMillis", samplingWin[0].getTime());
            win.put("endTimeMillis", samplingWin[1].getTime());
            root.put("keyParametersSamplingWindow", win);
        }
        return JsonUtils.toJsonString(root);
    }

    /**
     * 将编排器 {@link PhaseExecutionRecord} 列表转为入库用的 {@link #QC_PHASE_TIMELINES_KEY} 元素结构。
     */
    public static List<Map<String, Object>> qcPhaseTimelinesFromPhaseRecords(List<PhaseExecutionRecord> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PhaseExecutionRecord r : records) {
            if (r == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phaseCode", r.getPhaseId());
            row.put("phaseName", r.getDisplayName());
            row.put("estimatedSeconds", r.getEstimatedSeconds());
            Instant s = r.getStartInstant();
            Instant e = r.getEndInstant();
            if (s != null) {
                row.put("startTimeMillis", s.toEpochMilli());
            }
            if (e != null) {
                row.put("endTimeMillis", e.toEpochMilli());
            }
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseRootMap(String executionLog) {
        if (executionLog == null || executionLog.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        String trimmed = executionLog.trim();
        // 运行中等场景下 execution_log 可能为纯文本（如「校准任务执行中...」），非 JSON，不得走 parseNonStandardMap 以免抛错
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return Collections.emptyMap();
        }
        try {
            return JsonUtils.parseMap(trimmed, String.class, Object.class);
        } catch (Exception e) {
            try {
                return JsonUtils.parseNonStandardMap(executionLog, String.class, Object.class);
            } catch (Exception ignored) {
                return Collections.emptyMap();
            }
        }
    }

    /**
     * 供报表/解析使用的指标 Map：支持新结构 {@code result} 子对象，或旧结构扁平字段。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> metricsForReport(String executionLog) {
        Map<String, Object> root = parseRootMap(executionLog);
        if (root.isEmpty()) {
            return root;
        }
        Object resultObj = root.get("result");
        if (resultObj instanceof List) {
            return Collections.emptyMap();
        }
        if (resultObj instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) resultObj);
            Object status = root.get("statusMap");
            if (status instanceof Map) {
                Map<String, Object> st = (Map<String, Object>) status;
                if (!m.containsKey("isPass") && st.containsKey("isPass")) {
                    m.put("isPass", st.get("isPass"));
                }
            }
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>(root);
        m.remove("params");
        m.remove("statusMap");
        m.remove("keyParametersSnapshot");
        m.remove(QC_PHASE_TIMELINES_KEY);
        m.remove("keyParametersSamplingWindow");
        return m;
    }

    /**
     * 从 execution_log 根级 {@link #QC_PHASE_TIMELINES_KEY} 中解析「读数/采样」相位的时间窗，供报表关键参数回退查询使用。
     *
     * @return {@code [start, end]}，未配置或无法解析时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static Date[] resolveReadPhaseWindowForKeyParameters(String executionLog) {
        Map<String, Object> root = parseRootMap(executionLog);
        if (root.isEmpty()) {
            return null;
        }
        Object raw = root.get(QC_PHASE_TIMELINES_KEY);
        if (!(raw instanceof List)) {
            return null;
        }
        return resolveReadPhaseWindowFromTimelinesList((List<?>) raw);
    }

    /**
     * 从已解析的 {@link #QC_PHASE_TIMELINES_KEY} 列表中解析「读数 / CHECK / 浓度点采集」相位时间窗。
     */
    @SuppressWarnings("unchecked")
    public static Date[] resolveReadPhaseWindowFromTimelinesList(List<?> timelines) {
        if (timelines == null || timelines.isEmpty()) {
            return null;
        }
        for (Object o : timelines) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) o;
            if (!isReadSamplesPhaseRow(row)) {
                continue;
            }
            Date start = parsePhaseInstant(row.get("startTimeMillis"), row.get("startTime"));
            Date end = parsePhaseInstant(row.get("endTimeMillis"), row.get("endTime"));
            if (start != null && end != null && !end.before(start)) {
                return new Date[] { start, end };
            }
        }
        return null;
    }

    /**
     * 是否实际执行并完成「自动校准」相位：{@code qcPhaseTimelines} 中存在 {@code phaseCode=calibration} 且带非空
     * {@code endTimeMillis}（按需跳过时通常无结束时间）。
     */
    public static boolean hasCompletedCalibrationPhase(String executionLog) {
        Map<String, Object> root = parseRootMap(executionLog);
        if (root.isEmpty()) {
            return false;
        }
        Object raw = root.get(QC_PHASE_TIMELINES_KEY);
        if (!(raw instanceof List)) {
            return false;
        }
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) o;
            String code = String.valueOf(row.getOrDefault("phaseCode", "")).trim().toLowerCase(Locale.ROOT);
            if (!"calibration".equals(code)) {
                continue;
            }
            Object end = row.get("endTimeMillis");
            if (end instanceof Number && ((Number) end).longValue() > 0L) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReadSamplesPhaseRow(Map<String, Object> row) {
        Object code = row.get("phaseCode");
        if (code != null) {
            String c = String.valueOf(code).trim().toUpperCase(Locale.ROOT);
            if ("READ_SAMPLES".equals(c) || "READ_DATA".equals(c) || "SAMPLE".equals(c) || "COLLECT_SAMPLES".equals(c)) {
                return true;
            }
            if ("CHECK".equals(c)) {
                return true;
            }
            String lower = String.valueOf(code).trim().toLowerCase(Locale.ROOT);
            if ("check".equals(lower)) {
                return true;
            }
            if (lower.startsWith("point_")) {
                return true;
            }
        }
        Object name = row.get("phaseName");
        if (name == null) {
            return false;
        }
        String n = String.valueOf(name);
        return n.contains("读数") || n.contains("采集") || n.contains("检查（等待稳定");
    }

    private static Date parsePhaseInstant(Object millisObj, Object stringObj) {
        if (millisObj instanceof Number) {
            return new Date(((Number) millisObj).longValue());
        }
        if (millisObj != null) {
            try {
                return new Date(Long.parseLong(String.valueOf(millisObj).trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        if (stringObj == null) {
            return null;
        }
        String s = String.valueOf(stringObj).trim();
        if (s.isEmpty()) {
            return null;
        }
        for (String p : TIME_PATTERNS) {
            try {
                return new SimpleDateFormat(p).parse(s);
            } catch (ParseException ignored) {
                // try next
            }
        }
        return null;
    }

    /**
     * 从执行日志读取是否通过（优先 result.isPass，其次 statusMap，最后根级 isPass）。
     */
    @SuppressWarnings("unchecked")
    public static boolean readIsPass(String executionLog) {
        Map<String, Object> root = parseRootMap(executionLog);
        if (root.isEmpty()) {
            return false;
        }
        Object resultObj = root.get("result");
        if (resultObj instanceof Map) {
            Map<String, Object> rm = (Map<String, Object>) resultObj;
            if (rm.containsKey("isPass")) {
                return toBoolean(rm.get("isPass"));
            }
        }
        Object sm = root.get("statusMap");
        if (sm instanceof Map) {
            Object v = ((Map<String, Object>) sm).get("isPass");
            if (v != null) {
                return toBoolean(v);
            }
        }
        if (root.containsKey("isPass")) {
            return toBoolean(root.get("isPass"));
        }
        return false;
    }

    private static boolean toBoolean(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return false;
    }

    /** 读取指标 Map 中布尔字段（用于 isPass 等）。 */
    public static boolean readBoolean(Map<String, Object> metrics, String key) {
        if (metrics == null || key == null || !metrics.containsKey(key)) {
            return false;
        }
        return toBoolean(metrics.get(key));
    }

    /**
     * 从 execution_log 提取「不合格」时的简短文字说明，供报表备注列展示；不包含整段 JSON。
     * <p>任务成功结束且 {@link #readIsPass(String)} 为 true 时返回空串。</p>
     *
     * @param sectionTitle 可选前缀，如「零点核查」「跨度核查」「人工核查」
     */
    public static String remarkSummaryForFailedQC(String executionLog, String sectionTitle) {
        if (executionLog == null || executionLog.trim().isEmpty()) {
            return "";
        }
        if (readIsPass(executionLog)) {
            return "";
        }
        Map<String, Object> root = parseRootMap(executionLog);
        if (root.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        if (sectionTitle != null && !sectionTitle.trim().isEmpty()) {
            parts.add("【" + sectionTitle.trim() + "】");
        }
        Object smObj = root.get("statusMap");
        if (smObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sm = (Map<String, Object>) smObj;
            addTrimmedUnique(parts, stringOrEmpty(sm.get("errorMessage")));
            addTrimmedUnique(parts, stringOrEmpty(sm.get("resultMessage")));
        }
        Map<String, Object> m = metricsForReport(executionLog);
        if (!m.isEmpty()) {
            addTrimmedUnique(parts, formatMetricSnippet("结果值", m.get("resultValue")));
            addTrimmedUnique(parts, formatMetricSnippet("标准值", m.get("stdValue")));
            addTrimmedUnique(parts, formatMetricSnippet("设备值", m.get("deviceValue")));
            addTrimmedUnique(parts, formatMetricSnippet("漂移", m.get("drift")));
        }
        parts.removeIf(s -> s == null || s.trim().isEmpty());
        if (parts.isEmpty()) {
            return "";
        }
        return String.join(" ", parts);
    }

    private static void addTrimmedUnique(LinkedHashSet<String> parts, String s) {
        if (s == null) {
            return;
        }
        String t = s.trim();
        if (t.isEmpty() || parts.contains(t)) {
            return;
        }
        parts.add(t);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String formatMetricSnippet(String label, Object value) {
        String v = stringOrEmpty(value);
        if (v.isEmpty()) {
            return "";
        }
        return label + " " + v;
    }
}
