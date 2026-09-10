package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.AnalyzerOperatingStatusNormalRanges;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 报表静态格式化工具集（G-STRUCT-3：自 ReportGenerator 拆出）。
 * <p>仅收无状态纯函数：单位后缀、数值/列表安全转换、关键参数快照合并与补全、备注构建。
 * 与报表子类同包 {@code tasks.report}，历史访问级别为基类 protected static。</p>
 *
 * @author coffee
 */
public final class ReportFormatSupport {

    private ReportFormatSupport() {
    }

    /** 报表时刻展示格式（与旧 SimpleDateFormat 行为一致，站点时区）。 */
    private static final DateTimeFormatter REPORT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ReportGenerator.QC_REPORT_ZONE);

    public static String fmtReportTime(Instant t) {
        return t != null ? REPORT_TIME_FORMATTER.format(t) : "";
    }

    /** 报告日期归日：记录时刻在站点时区下的日历日。 */
    public static LocalDate reportDateOf(Instant t) {
        return t.atZone(ReportGenerator.QC_REPORT_ZONE).toLocalDate();
    }

    /** 报告归日时区直取（子类分桶用）。 */
    public static ZoneId reportZone() {
        return ReportGenerator.QC_REPORT_ZONE;
    }

    /**
     * 安全转换 Number 类型到 Float
     * JSON 解析时数字可能是 Double、Float、Integer 等类型
     * @param obj 待转换的对象
     * @return Float 值，如果无法转换则返回 null
     */
    public static Float convertToFloat(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Number) {
            return ((Number) obj).floatValue();
        }
        if (obj instanceof String) {
            try {
                return Float.parseFloat((String) obj);
            } catch (NumberFormatException e) {
                ReportGenerator.logger.warn("Failed to convert string to float: " + obj, e);
                return null;
            }
        }
        ReportGenerator.logger.warn("Unexpected type for float conversion: " + obj.getClass().getName());
        return null;
    }

    /**
     * 安全转换 List 到 List&lt;Float&gt;
     * JSON 解析时列表中的数字可能是 Double 类型
     * @param obj 待转换的对象
     * @return List&lt;Float&gt;，如果无法转换则返回空列表
     */
    public static List<Float> convertToFloatList(Object obj) {
        List<Float> result = new ArrayList<>();
        if (obj == null) {
            return result;
        }
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                Float floatValue = convertToFloat(item);
                if (floatValue != null) {
                    result.add(floatValue);
                }
            }
        }
        return result;
    }

    /** 报表气种是否为 CO（记录字段存 ParameterEnum code，兼容名称）。 */
    public static boolean isCoReportGas(String gasTypeCode) {
        return ParameterEnum.CO.getCode().equals(gasTypeCode) || "CO".equalsIgnoreCase(gasTypeCode);
    }

    /**
     * 编排器 / execution_log 中的浓度一律为 ppb。
     * CO 报表展示 ppm，需 ÷1000；其余气种保持 ppb。
     * <p>钢瓶标气等已按展示单位读出的字段不要走此方法，只用 {@link #appendConcUnit}。
     */
    public static double ppbToReportNumber(double ppb, String gasTypeCode) {
        return isCoReportGas(gasTypeCode) ? ppb / 1000.0 : ppb;
    }

    /** 报表浓度最多两位小数；整数不补零，例如 {@code 40}、{@code 399.5}。 */
    public static String formatConcDisplayNumber(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "";
        }
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** 将 ppb 数值转为报表单元格：CO → {@code 40 ppm}，其余 → {@code 400 ppb}。 */
    public static String formatPpbWithDisplayUnit(double ppb, String gasTypeCode) {
        return appendConcUnit(formatConcDisplayNumber(ppbToReportNumber(ppb, gasTypeCode)), gasTypeCode);
    }

    /**
     * 线性拟合截距 b 与浓度同单位（ppb）。CO 报表按 ppm 展示时同步 ÷1000；斜率 a、相关系数 r 无量纲，不换算。
     */
    public static String formatPpbInterceptForDisplay(String rawIntercept, String gasTypeCode) {
        if (rawIntercept == null || rawIntercept.trim().isEmpty()) {
            return "";
        }
        try {
            double v = Double.parseDouble(rawIntercept.trim());
            if (isCoReportGas(gasTypeCode)) {
                v = v / 1000.0;
            }
            return formatConcDisplayNumber(v);
        } catch (NumberFormatException e) {
            return rawIntercept;
        }
    }

    public static String appendConcUnit(String raw, String gasTypeCode) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim();
        if (s.endsWith("%")) {
            return s;
        }
        String unit;
        String numPart;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ppm")) {
            unit = " ppm";
            numPart = s.substring(0, s.length() - 3).trim();
        } else if (lower.endsWith("ppb")) {
            unit = " ppb";
            numPart = s.substring(0, s.length() - 3).trim();
        } else {
            unit = isCoReportGas(gasTypeCode) ? " ppm" : " ppb";
            numPart = s;
        }
        Double parsed = tryParseConcNumber(numPart);
        if (parsed != null) {
            return formatConcDisplayNumber(parsed) + unit;
        }
        return lower.endsWith("ppm") || lower.endsWith("ppb") ? s : s + unit;
    }

    public static String appendDriftUnit(String raw, String unit) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim();
        if (s.endsWith("%")) {
            return s;
        }
        String unitNorm = unit == null ? "" : unit;
        String numPart = s;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ppm")) {
            unitNorm = " ppm";
            numPart = s.substring(0, s.length() - 3).trim();
        } else if (lower.endsWith("ppb")) {
            unitNorm = " ppb";
            numPart = s.substring(0, s.length() - 3).trim();
        }
        Double parsed = tryParseConcNumber(numPart);
        if (parsed != null) {
            return formatConcDisplayNumber(parsed) + unitNorm;
        }
        if (lower.endsWith("ppm") || lower.endsWith("ppb")) {
            return s;
        }
        return s + unitNorm;
    }

    private static Double tryParseConcNumber(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            String stripped = stripNumericConcentration(raw);
            if (stripped.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(stripped);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    /**
     * 标定侧响应值：仅取编排器复测均值 {@code verificationValue}，已校准但缺失则空，不回退 {@code stdValue}。
     */
    public static Object pickVerificationValue(Map<String, Object> executionLogMap) {
        if (executionLogMap == null) {
            return null;
        }
        Object v = executionLogMap.get("verificationValue");
        if (v == null) {
            v = executionLogMap.get("verification_value");
        }
        if (v == null) {
            return null;
        }
        if (v instanceof String && ((String) v).trim().isEmpty()) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if ("null".equalsIgnoreCase(s)) {
            return null;
        }
        return v;
    }

    /** 标定响应展示：无复测值为空；CO 从 ppb 换到 ppm。 */
    public static String formatCalibrationResponse(Object calSrc, String parameterCode) {
        if (calSrc == null) {
            return "";
        }
        if (isCoReportGas(parameterCode) && calSrc instanceof Number) {
            return ((Number) calSrc).doubleValue() / 1000 + "";
        }
        return String.valueOf(calSrc);
    }

    /** 跨度漂移为相对百分比，不是浓度单位。 */
    public static String formatSpanDriftPercentDisplay(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim();
        if (s.endsWith("%")) {
            return s;
        }
        return s + " %";
    }

    public static List<String> formatFloatListWithConcUnit(List<Float> values, String gasTypeCode) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (Float f : values) {
            if (f == null) {
                out.add("");
            } else {
                out.add(formatPpbWithDisplayUnit(f.doubleValue(), gasTypeCode));
            }
        }
        return out;
    }

    public static String formatRelativeStandardDeviationPercent(Float rsd) {
        if (rsd == null) {
            return "";
        }
        String s = String.valueOf(rsd).trim();
        if (s.endsWith("%")) {
            return s;
        }
        return s + " %";
    }

    /** 指标 Map 中按候选 key 顺序取第一个非空值（兼容历史 JSON 字段名）。 */
    public static Object firstNonNullMetric(Map<String, Object> m, String... keys) {
        if (m == null || keys == null) {
            return null;
        }
        for (String k : keys) {
            if (k != null && m.containsKey(k) && m.get(k) != null) {
                return m.get(k);
            }
        }
        return null;
    }

    public static String metricString(Map<String, Object> m, String key) {
        Object v = m == null || key == null ? null : m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public static String ensurePercentSuffix(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim();
        if (s.endsWith("%")) {
            return s;
        }
        return s + " %";
    }

    public static String formatEfficiencyPercent(Float f) {
        if (f == null) {
            return "";
        }
        return ensurePercentSuffix(String.valueOf(f));
    }

    /** 从「60 ppb」等字符串中抽出数值部分，便于再统一补单位。 */
    public static String stripNumericConcentration(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim().replaceAll("[^0-9.Ee+-]", "");
        return s.isEmpty() ? raw.trim() : s;
    }

    /** 取第一个非空白字符串；全空则返回空串（{@code null} 段跳过）。 */
    public static String firstNonBlank(String... parts) {
        if (parts == null) {
            return "";
        }
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String t = p.trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    /** 质控记录创建人引用：QcmRecord 仅 {@code createdBy} 单通道（旧 BaseEntity 双通道已随实体换代移除）。 */
    public static String recordCreatorRef(QcmRecord r) {
        return r == null ? "" : firstNonBlank(r.getCreatedBy());
    }

    /** 质控记录更新人引用：QcmRecord 仅 {@code updatedBy} 单通道。 */
    public static String recordUpdaterRef(QcmRecord r) {
        return r == null ? "" : firstNonBlank(r.getUpdatedBy());
    }

    /**
     * 合并各质控记录 execution_log 根级 {@code keyParametersSnapshot}（顺序：先零后跨）；无快照时返回空列表。
     */
    public static List<Map<String, Object>> mergeKeyParameterSnapshotsFromRecords(QcmRecord zero, QcmRecord span) {
        List<Map<String, Object>> merged = new ArrayList<>();
        appendKeySnapshotRows(merged, zero);
        appendKeySnapshotRows(merged, span);
        return merged;
    }

    public static void appendKeySnapshotRows(List<Map<String, Object>> merged, QcmRecord r) {
        if (r == null || r.getExecutionLog() == null || r.getExecutionLog().trim().isEmpty()) {
            return;
        }
        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(r.getExecutionLog());
        Object raw = root.get("keyParametersSnapshot");
        if (!(raw instanceof List)) {
            return;
        }
        for (Object row : (List<?>) raw) {
            if (row instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) row);
                merged.add(copy);
            }
        }
    }

    public static Instant[] firstReadPhaseWindowForKeyParameters(QcmRecord... records) {
        for (QcmRecord r : records) {
            if (r == null) {
                continue;
            }
            Date[] w = QualityControlExecutionLogHelper.resolveReadPhaseWindowForKeyParameters(r.getExecutionLog());
            if (w != null) {
                return new Instant[]{w[0].toInstant(), w[1].toInstant()};
            }
        }
        return null;
    }

    /**
     * 关键参数「检查值」补单位：兼容仅有数值的 execution_log 快照行；并在 {@code tRange} 为空时填入分析仪运行状况参考正常范围。
     * 入口先剔除主浓度行（与本次质控通入标气无关，历史快照残留也不入表）。
     */
    public static void enrichKeyParameterRowsForReport(List<Map<String, Object>> rows, String gasTypeCode) {
        if (rows == null) {
            return;
        }
        LogicDeviceReportSupport.removePrimaryGasConcentrationRows(rows);
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Object tv = row.get("tValue");
            String val = tv == null ? "" : String.valueOf(tv).trim();
            if (!val.isEmpty() && !keyParamValueLooksLikeHasUnit(val)) {
                Object tNameObj = row.get("tName");
                String suffix = inferKeyParameterUnitSuffix(String.valueOf(tNameObj == null ? "" : tNameObj), gasTypeCode);
                if (!suffix.isEmpty()) {
                    row.put("tValue", val + suffix);
                }
            }
            Object tr = row.get("tRange");
            String trs = tr == null ? "" : String.valueOf(tr).trim();
            if (trs.isEmpty()) {
                Object tNameObj = row.get("tName");
                String nr = AnalyzerOperatingStatusNormalRanges.lookupByParameterCode(
                        gasTypeCode, String.valueOf(tNameObj == null ? "" : tNameObj));
                if (nr != null && !nr.isEmpty()) {
                    row.put("tRange", nr);
                }
            }
        }
    }

    private static boolean keyParamValueLooksLikeHasUnit(String val) {
        String v = val.toLowerCase(Locale.ROOT);
        return v.endsWith("ppm")
                || v.endsWith("ppb")
                || v.endsWith("%")
                || v.contains("l/min")
                || v.contains("m³/h")
                || v.contains("m3/h")
                || v.endsWith("hpa")
                || v.endsWith("kpa")
                || v.endsWith(" pa")
                || v.endsWith("°c")
                || v.endsWith("℃");
    }

    private static String inferKeyParameterUnitSuffix(String name, String gasTypeCode) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.contains("流量")) {
            return " L/min";
        }
        if (name.contains("压力") || name.contains("气压") || name.contains("样气压力")) {
            return " hPa";
        }
        if (name.contains("温度") || name.contains("机箱") || name.contains("气室")) {
            return " °C";
        }
        if (name.contains("湿度")) {
            return " %";
        }
        if (name.contains("浓度") || name.contains("NO") || name.contains("SO") || name.contains("O₃") || name.contains("O3")
                || name.contains("CO")) {
            return isCoReportGas(gasTypeCode) ? " ppm" : " ppb";
        }
        return "";
    }

    /**
     * 零跨报表备注：仅在不通过时汇总可读原因，不拼接整段 execution_log JSON。
     */
    public static String buildZeroSpanReportRemark(QcmRecord zero, QcmRecord span) {
        List<String> lines = new ArrayList<>();
        if (zero != null && !QualityControlExecutionLogHelper.readIsPass(zero.getExecutionLog())) {
            Map<String, Object> rq = QualityControlExecutionLogHelper.parseRootMap(zero.getExecutionLog());
            String s = QualityControlExecutionLogHelper.remarkSummaryForFailedQC(zero.getExecutionLog(), "零点核查");
            if (s.isEmpty() && !rq.isEmpty()) {
                s = plainEvaluationIfNotJson(zero.getResultEvaluation());
            }
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        if (span != null && !QualityControlExecutionLogHelper.readIsPass(span.getExecutionLog())) {
            Map<String, Object> rq = QualityControlExecutionLogHelper.parseRootMap(span.getExecutionLog());
            String s = QualityControlExecutionLogHelper.remarkSummaryForFailedQC(span.getExecutionLog(), "跨度核查");
            if (s.isEmpty() && !rq.isEmpty()) {
                s = plainEvaluationIfNotJson(span.getResultEvaluation());
            }
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        return String.join("\n", lines);
    }

    /** 结果评价字段：若非 JSON 则作为备注兜底展示。 */
    public static String plainEvaluationIfNotJson(String evaluation) {
        if (evaluation == null) {
            return "";
        }
        String t = evaluation.trim();
        if (t.isEmpty()) {
            return "";
        }
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            return "";
        }
        return t;
    }

    /** 空关键参数行占位（与历史行为一致：无快照也无设备读数时为空列表）。 */
    public static List<Map<String, Object>> emptyKeyParameters() {
        return Collections.emptyList();
    }
}
