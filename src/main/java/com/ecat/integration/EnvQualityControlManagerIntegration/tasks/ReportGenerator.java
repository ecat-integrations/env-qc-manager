package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.integration.EnvQualityControlManagerIntegration.util.AnalyzerOperatingStatusNormalRanges;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.JsonUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ZeroSpanDayPairSelector;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.service.ISysUserService;
import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum.*;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.*;


/**
 * ReportGenerator
 * <p>质控报告生成器</p>
 * <p>可以生成：</p>
 * <ul>
 *     <li>仪器运行状况检查/校准记录表</li>
 *     <li>仪器多点校准记录表</li>
 *     <li>仪器精密度审核记录表</li>
 *     <li>仪器准确度审核记录表</li>
 *     <li>氮氧化物转换效率测试记录表</li>
 *     <li>臭氧校准设备量值传递记录表</li>
 * </ul>
 * <p>结果说明：</p>
 * <table border="1">
 *     <tr>
 *         <td width="40%">GenZeroAndSpanReport</td>
 *         <td width="100%">支持--------------------------------------------------------------</td>
 *     </tr>
 *     <tr>
 *         <td>GenMultiCheckReport</td>
 *         <td>支持</td>
 *     </tr>
 *     <tr>
 *         <td>GenPrecisionReport</td>
 *         <td>支持</td>
 *     </tr>
 *     <tr>
 *         <td>GenAccuracyReport</td>
 *         <td>支持</td>
 *     </tr>
 *     <tr>
 *         <td>GenConversionReport</td>
 *         <td>仅支持 NOx</td>
 *     </tr>
 *     <tr>
 *         <td>GenCalibrationReport</td>
 *         <td>不支持</td>
 *     </tr>
 * </table>
 * @author caohongbo
 */
public class ReportGenerator {

    /** 与质控记录 {@code start_time} 归日一致，用于日报分桶与 {@code report_date}。 */
    public static final ZoneId QC_REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    private EcatCore core;
    protected EcatCoreRuoyiIntegration mry;

    /** 同文件内 {@code Gen*} 报表子类会写日志，需对子类可见（非 private）。 */
    protected static final Logger logger = LoggerFactory.getLogger(ReportGenerator.class);

    private IEnvQualityControlRecordsService envQualityControlRecordsService;

    public String reportType;
    // 存储报告最终结果
    @Getter
    public EnvQualityControlReport report;

    public ReportGenerator() {
    }

    public ReportGenerator(EcatCore core) {
        this.core = core;
    }
    
    protected String getStdGasConcentration(String gasType) {
        return LogicDeviceReportSupport.readStandardGasCylinderConcentration(core, gasType);
    }

    /**
     * 从标准气逻辑设备补充 {@link EnvQualityControlReport} 的标气来源/编号（读到非空则覆盖原值）。
     */
    protected void applyStandardGasSourceAndNoFromLogicDevices(EnvQualityControlReport target, String gasParameterName) {
        if (core == null || target == null || gasParameterName == null || gasParameterName.trim().isEmpty()) {
            return;
        }
        try {
            String[] pair = LogicDeviceReportSupport.tryReadStandardGasSourceAndNo(core, gasParameterName);
            if (pair == null) {
                return;
            }
            if (pair[0] != null && !pair[0].isEmpty()) {
                target.setGasSource(pair[0]);
            }
            if (pair[1] != null && !pair[1].isEmpty()) {
                target.setGasNo(pair[1]);
            }
        } catch (Exception e) {
            logger.debug("applyStandardGasSourceAndNoFromLogicDevices skipped: {}", e.getMessage());
        }
    }

    /**
     * 将 {@link EnvQualityControlReport#getGasSource()} 与 {@link EnvQualityControlReport#getGasNo()} 拼入各报表子类的
     * {@code gasSourceAndNo} 展示字段（若子类定义了该字段）。
     */
    protected void refreshReportGasSourceAndNoCombined(EnvQualityControlReport r) {
        if (r == null) {
            return;
        }
        String g = r.getGasSource() != null ? r.getGasSource().trim() : "";
        String n = r.getGasNo() != null ? r.getGasNo().trim() : "";
        String combo = (g + " " + n).trim();
        if (r instanceof ZeroAndSpanReport) {
            ((ZeroAndSpanReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof MultiCheckReport) {
            ((MultiCheckReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof PrecisionReport) {
            ((PrecisionReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof AccuracyReport) {
            ((AccuracyReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof ConversionReport) {
            ((ConversionReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof AuditSpanReport) {
            ((AuditSpanReport) r).setGasSourceAndNo(combo);
        }
    }

    /**
     * 关键参数：优先使用 execution_log 中已存快照；否则读当前逻辑设备属性。
     * {@code beginPickTime}/{@code endPickTime} 在存在 {@link QualityControlExecutionLogHelper#QC_PHASE_TIMELINES_KEY}
     * 读数相位时已解析为该窗口（供将来按时刻查询历史读数时沿用），当前实现仍与即时读数一致。
     */
    protected List<Map<String, Object>> queryKeyParameters(
            Date beginPickTime, Date endPickTime, String param, String deviceId) {
        return LogicDeviceReportSupport.buildAnalyzerKeyParameters(core, param);
    }

    /**
     * 合并各质控记录 execution_log 根级 {@code keyParametersSnapshot}（顺序：先零后跨）；无快照时返回空列表。
     */
    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> mergeKeyParameterSnapshotsFromRecords(
            EnvQualityControlRecords zero,
            EnvQualityControlRecords span) {
        List<Map<String, Object>> merged = new ArrayList<>();
        appendKeySnapshotRows(merged, zero);
        appendKeySnapshotRows(merged, span);
        return merged;
    }

    @SuppressWarnings("unchecked")
    protected static void appendKeySnapshotRows(List<Map<String, Object>> merged, EnvQualityControlRecords r) {
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

    protected static Date[] firstReadPhaseWindowForKeyParameters(EnvQualityControlRecords... records) {
        for (EnvQualityControlRecords r : records) {
            if (r == null) {
                continue;
            }
            Date[] w = QualityControlExecutionLogHelper.resolveReadPhaseWindowForKeyParameters(r.getExecutionLog());
            if (w != null) {
                return w;
            }
        }
        return null;
    }

    /**
     * 逻辑层未就绪时的物理设备 ID（与历史默认表一致）。
     */
    private static String fallbackPhysicalDeviceIdForReportGas(String param) {
        if (param == null) {
            return "sms-qc";
        }
        switch (param.trim().toUpperCase(Locale.ROOT)) {
            case "SO2":
                return "esa-so2";
            case "NO2":
                return "esa-no2";
            case "O3":
                return "esa-o3";
            case "CO":
                return "esa-co";
            default:
                return "sms-qc";
        }
    }

    /**
     * 安全转换 Number 类型到 Float
     * JSON 解析时数字可能是 Double、Float、Integer 等类型
     * @param obj 待转换的对象
     * @return Float 值，如果无法转换则返回 null
     */
    protected Float convertToFloat(Object obj) {
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
                logger.warn("Failed to convert string to float: " + obj, e);
                return null;
            }
        }
        logger.warn("Unexpected type for float conversion: " + obj.getClass().getName());
        return null;
    }

    /**
     * 安全转换 List 到 List<Float>
     * JSON 解析时列表中的数字可能是 Double 类型
     * @param obj 待转换的对象
     * @return List<Float>，如果无法转换则返回空列表
     */
    protected List<Float> convertToFloatList(Object obj) {
        List<Float> result = new ArrayList<>();
        if (obj == null) {
            return result;
        }
        if (obj instanceof List) {
            @SuppressWarnings("unchecked")
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

    /**
     * 根据气态参数获取物理 {@link DeviceBase}。
     * <p>优先从 {@link EcatCore#getDeviceRegistry()} 中标准分析仪逻辑设备的 {@code mappings} 解析
     * {@code device_id}；若无映射则按历史约定降级为 esa-* / sms-qc。</p>
     *
     * @param param 气态参数 (SO2, NO2, CO, O3)
     * @return 设备对象；core 未初始化或设备不存在时可能为 {@code null}
     */
    public DeviceBase getDeviceInfo(String param) {
        if (core == null) {
            logger.error("EcatCore 未初始化，无法解析设备");
            return null;
        }
        String deviceId = LogicDeviceReportSupport.resolveAnalyzerPhysicalDeviceId(core, param);
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = fallbackPhysicalDeviceIdForReportGas(param);
            logger.warn(
                    "逻辑设备未解析到物理 device_id，气体 {} 使用降级设备 {}；请检查 logicdevice.* 的 data.mappings 是否为主浓度属性配置了 device_id（无物理设备/仅占位时无法解析）。将 LogicDeviceReportSupport 调至 DEBUG 可查看 mappingsKeys 与逻辑入口是否注册",
                    param,
                    deviceId);
        }

        logger.debug("获取设备信息: {} -> {}", param, deviceId);
        DeviceRegistry deviceRegistry = core.getDeviceRegistry();
        DeviceBase device = deviceRegistry.getDeviceByID(deviceId);
        
        if (device == null) {
            logger.error("未找到设备: {}, 请检查设备配置", deviceId);
        }
        
        return device;
    }

    protected static String appendConcUnit(String raw, String gasTypeCode) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim();
        if (s.endsWith("ppm") || s.endsWith("ppb") || s.endsWith("%")) {
            return s;
        }
        String u = CO.getCode().equals(gasTypeCode) ? " ppm" : " ppb";
        return s + u;
    }

    protected static String appendDriftUnit(String raw, String unit) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim();
        if (s.endsWith("ppm") || s.endsWith("ppb") || s.endsWith("%")) {
            return s;
        }
        return s + unit;
    }

    /**
     * 标定侧响应值：编排器/任务写入的 {@code verificationValue} 优先，否则回退 {@code stdValue}（与历史数据兼容）。
     */
    protected static Object pickVerificationOrStd(Map<String, Object> executionLogMap) {
        if (executionLogMap == null) {
            return null;
        }
        Object v = executionLogMap.get("verificationValue");
        if (v == null) {
            v = executionLogMap.get("verification_value");
        }
        if (v == null) {
            v = executionLogMap.get("stdValue");
        }
        return v;
    }

    /** 跨度漂移为相对百分比，不是浓度单位。 */
    protected static String formatSpanDriftPercentDisplay(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim();
        if (s.endsWith("%")) {
            return s;
        }
        return s + " %";
    }

    protected static List<String> formatFloatListWithConcUnit(List<Float> values, String gasTypeCode) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (Float f : values) {
            if (f == null) {
                out.add("");
            } else {
                out.add(appendConcUnit(String.valueOf(f), gasTypeCode));
            }
        }
        return out;
    }

    protected static String formatRelativeStandardDeviationPercent(Float rsd) {
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
    protected static Object firstNonNullMetric(Map<String, Object> m, String... keys) {
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

    protected static String metricString(Map<String, Object> m, String key) {
        Object v = m == null || key == null ? null : m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    protected static String ensurePercentSuffix(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim();
        if (s.endsWith("%")) {
            return s;
        }
        return s + " %";
    }

    protected static String formatEfficiencyPercent(Float f) {
        if (f == null) {
            return "";
        }
        return ensurePercentSuffix(String.valueOf(f));
    }

    /** 从「60 ppb」等字符串中抽出数值部分，便于再统一补单位。 */
    protected static String stripNumericConcentration(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim().replaceAll("[^0-9.Ee+-]", "");
        return s.isEmpty() ? raw.trim() : s;
    }

    /** 取第一个非空白字符串；全空则返回空串（{@code null} 段跳过）。 */
    protected static String firstNonBlank(String... parts) {
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

    /**
     * 质控记录创建人引用：子类字段 {@code createdBy} 与 {@link com.ruoyi.common.core.domain.BaseEntity#createBy}
     * 可能只填其一（RuoYi 默认常写 {@code create_by}）。
     */
    protected static String recordCreatorRef(EnvQualityControlRecords r) {
        return r == null ? "" : firstNonBlank(r.getCreatedBy(), r.getCreateBy());
    }

    /** 质控记录更新人引用：{@code updatedBy} 与 {@code updateBy} 可能只填其一。 */
    protected static String recordUpdaterRef(EnvQualityControlRecords r) {
        return r == null ? "" : firstNonBlank(r.getUpdatedBy(), r.getUpdateBy());
    }

    protected void ensureRuoyiIntegration() {
        if (mry == null && core != null) {
            try {
                mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry().getIntegration("integration-ecat-core-ruoyi");
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private ISysUserService tryResolveUserService() {
        ensureRuoyiIntegration();
        if (mry != null) {
            try {
                ISysUserService svc = mry.getSpringBean(ISysUserService.class);
                if (svc != null) {
                    return svc;
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        try {
            return SpringUtils.getBean(ISysUserService.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeUnsignedLongUserId(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String displayLabelFromSysUser(SysUser u) {
        if (u == null) {
            return "";
        }
        String nick = u.getNickName();
        if (nick != null && !nick.trim().isEmpty()) {
            return nick.trim();
        }
        String un = u.getUserName();
        return un == null ? "" : un.trim();
    }

    private String displayNameFromLoginUserOnly() {
        try {
            LoginUser lu = SecurityUtils.getLoginUser();
            if (lu == null || lu.getUser() == null) {
                return "";
            }
            String nick = lu.getUser().getNickName();
            if (nick != null && !nick.trim().isEmpty()) {
                return nick.trim();
            }
        } catch (Throwable ignored) {
            // 无 Security 上下文
        }
        return "";
    }

    /**
     * 报表填表人/复核人展示名：按 Ruoyi 用户表解析「用户名称」（{@code nick_name}）；与当前登录用户一致时再走 Security。
     * 优先通过 {@link EcatCoreRuoyiIntegration} 取 {@link ISysUserService}，以兼容部分线程中 {@link SpringUtils} 不可用的情况。
     */
    protected String resolveReportPersonDisplayName(String storedUserRef) {
        if (storedUserRef == null || storedUserRef.trim().isEmpty()) {
            return "";
        }
        String key = storedUserRef.trim();
        ISysUserService userService = tryResolveUserService();
        if (userService != null) {
            try {
                SysUser u = userService.selectUserByUserName(key);
                if (u == null && looksLikeUnsignedLongUserId(key)) {
                    u = userService.selectUserById(Long.parseLong(key));
                }
                if (u != null) {
                    String label = displayLabelFromSysUser(u);
                    if (!label.isEmpty()) {
                        return label;
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        try {
            String u = SecurityUtils.getUsername();
            if (u != null && !u.trim().isEmpty() && u.trim().equalsIgnoreCase(key) && !"anonymousUser".equalsIgnoreCase(u.trim())) {
                String fromLogin = displayNameFromLoginUserOnly();
                if (!fromLogin.isEmpty()) {
                    return fromLogin;
                }
                return u.trim();
            }
        } catch (Throwable ignored) {
            // 无 Security 上下文
        }
        return key;
    }

    /**
     * 报告「填表人」：解析逻辑同 {@link #resolveReportPersonDisplayName(String)}；若无创建人引用则尝试当前登录用户昵称（用于预览等场景）。
     */
    protected String resolveReportFilerDisplayName(String recordCreatorRef) {
        String resolved = resolveReportPersonDisplayName(recordCreatorRef);
        if (!resolved.isEmpty()) {
            return resolved;
        }
        return displayNameFromLoginUserOnly();
    }

    /**
     * 关键参数「检查值」补单位：兼容仅有数值的 execution_log 快照行；并在 {@code tRange} 为空时填入分析仪运行状况参考正常范围。
     */
    protected static void enrichKeyParameterRowsForReport(List<Map<String, Object>> rows, String gasTypeCode) {
        if (rows == null) {
            return;
        }
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
            return CO.getCode().equals(gasTypeCode) ? " ppm" : " ppb";
        }
        return "";
    }

    /**
     * 零跨报表备注：仅在不通过时汇总可读原因，不拼接整段 execution_log JSON。
     */
    protected static String buildZeroSpanReportRemark(EnvQualityControlRecords zero, EnvQualityControlRecords span) {
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
    protected static String plainEvaluationIfNotJson(String evaluation) {
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

    /**
     * 解析质控记录中的用于报告的数据
     * @param records 质控记录
     * @return report 质控报告
     */
    private EnvQualityControlReport parseRecordToReport(List<EnvQualityControlRecords> records) {
        throw new UnsupportedOperationException();
    }

    private EnvQualityControlReport parseRecordToReport(EnvQualityControlRecords record) {
        throw new UnsupportedOperationException();
    }

    /**
     * 组装报告数据
     * @return
     */
    public Map<String,  Object> constructReportContent() {
        throw new UnsupportedOperationException();
    }

    /**
     * 生成某段时间的报告
     * @param startTime 开始时间
     * @param endTime  结束时间
     */
    public List<EnvQualityControlReport> generate(Date startTime, Date endTime) {
        // 查询质控记录表，获取某段时间内的质控记录，遍历成功完成的记录，存入质控报告表，注意零跨的特殊处理
        if (mry == null) {
            mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry().getIntegration("integration-ecat-core-ruoyi");
        }
        envQualityControlRecordsService = mry.getSpringBean(IEnvQualityControlRecordsService.class);
        List<EnvQualityControlRecords> recordResults = envQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(startTime, endTime, null, 2L);
        logger.info("ReportGenerator.generate.selectEnvQualityControlRecordsByTypeTime("+startTime +","+ endTime+")"
                + "recordResults -> " + recordResults);

        List<EnvQualityControlReport> reports = new ArrayList<>();
        if (recordResults.isEmpty()) return reports;

        // 零跨时间线
        Map<String, List<EnvQualityControlRecords>> zeroSpanLineMap = new HashMap<>();
        zeroSpanLineMap.put(ParameterEnum.SO2.getCode(), new ArrayList<>());
        zeroSpanLineMap.put(ParameterEnum.NO2.getCode(), new ArrayList<>());
        zeroSpanLineMap.put(ParameterEnum.O3.getCode(), new ArrayList<>());
        zeroSpanLineMap.put(CO.getCode(), new ArrayList<>());

        // 人工核查时间线
        Map<String, List<EnvQualityControlRecords>> auditSpanLineMap = new HashMap<>();
        auditSpanLineMap.put(ParameterEnum.SO2.getCode(), new ArrayList<>());
        auditSpanLineMap.put(ParameterEnum.NO2.getCode(), new ArrayList<>());
        auditSpanLineMap.put(ParameterEnum.O3.getCode(), new ArrayList<>());
        auditSpanLineMap.put(CO.getCode(), new ArrayList<>());

        // 遍历利质控记录列表
        for (EnvQualityControlRecords recordResult : recordResults) {
            try {
                // 将成功完成的记录存入质控报告表
                // 默认只取成功的记录
                // if (recordResult.getExecutionStatus().equals(ExecutionStatusEnum.SUCCESS.getCode())) {}
                // else { do nothing }

                // 未成功的质控记录，不生成质控报告，而在质控记录里溯查原因，并记录在质控记录表里
                if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.ZERO_CHECK.getCode()) ||
                        recordResult.getQualityControlType().equals(QualityControlTypeEnum.SPAN_CHECK.getCode())) {
                    // 分参数 放入零跨时间线，等待滑动时间窗口处理零跨聚合，后续实现时间线处理...
                    String param = recordResult.getParameter();
                    // 如果param属于zeroSpanLineMap的key,则将recordResult加入到zeroSpanLine
                    if (zeroSpanLineMap.containsKey(param)) {
                        zeroSpanLineMap.get(param).add(recordResult);
                    }
                    // throw new UnsupportedOperationException("暂不支持零跨聚合");

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.MULTI_CHECK.getCode())) {
                    GenMultiCheckReport genReport = new GenMultiCheckReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.PRECISION_CHECK.getCode())) {
                    GenPrecisionReport genReport = new GenPrecisionReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.ACCURACY_CHECK.getCode())) {
                    GenAccuracyReport genReport = new GenAccuracyReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.CONVERSION_CHECK.getCode())) {
                    GenConversionReport genReport = new GenConversionReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals("calibration_check")) {
                    GenTransferAndTraceReport genReport = new GenTransferAndTraceReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.AUDIT_SPAN_CHECK.getCode())) {
                    // 目的是将当天的所有人工核查聚合到一张报告中
                    String param = recordResult.getParameter();
                    if (auditSpanLineMap.containsKey(param)) {
                        auditSpanLineMap.get(param).add(recordResult);
                    }

                } else {
                    throw new UnsupportedOperationException("暂不支持该类型质控");
                }
                // reportResult.setReportContent(reportContent);
            } catch (UnsupportedOperationException e) {
                System.out.println("Generate report failed for record: " + recordResult + ", reason: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 遍历 zeroSpanLineMap：按参数、按自然日（start_time 在 QC_REPORT_ZONE）分桶后择优配对，每日至多一张零跨报告
        for (Map.Entry<String, List<EnvQualityControlRecords>> entry : zeroSpanLineMap.entrySet()) {
            List<EnvQualityControlRecords> zeroSpanLine = entry.getValue();
            if (zeroSpanLine.isEmpty()) {
                continue;
            }
            zeroSpanLine.sort(Comparator.comparing(EnvQualityControlRecords::getStartTime, Comparator.nullsFirst(Comparator.naturalOrder())));
            Map<LocalDate, List<EnvQualityControlRecords>> byDay = new TreeMap<>();
            for (EnvQualityControlRecords r : zeroSpanLine) {
                if (r.getStartTime() == null) {
                    continue;
                }
                LocalDate day = r.getStartTime().toInstant().atZone(QC_REPORT_ZONE).toLocalDate();
                byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(r);
            }
            for (Map.Entry<LocalDate, List<EnvQualityControlRecords>> dayEntry : byDay.entrySet()) {
                List<EnvQualityControlRecords> chosen = ZeroSpanDayPairSelector.select(dayEntry.getValue());
                if (chosen.isEmpty()) {
                    continue;
                }
                reports.add(new GenZeroAndSpanReport(core, dayEntry.getKey(), chosen).getReport());
            }
        }

        // 遍历auditSpanLineMap的各个参数，处理人工核查时间线上的所有跨度质控
        for (Map.Entry<String, List<EnvQualityControlRecords>> entry : auditSpanLineMap.entrySet()) {
            List<EnvQualityControlRecords> auditSpanLine = entry.getValue();
            if (!auditSpanLine.isEmpty()) {
                GenAuditSpanReport genReport = new GenAuditSpanReport(core, auditSpanLine);
                reports.add(genReport.getReport());
            }
        }


        return reports;
    }

    /**
     * 单条成功质控记录的报告预览数据（与定时任务 Gen* 报表生成器同源），供前端「质控结果」弹窗复用。
     */
    public static Map<String, Object> buildSingleRecordPreviewPayload(EcatCore core, EnvQualityControlRecords r) {
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(r, "record");
        if (r.getStartTime() == null) {
            throw new IllegalArgumentException("record.startTime is required");
        }
        LocalDate day = r.getStartTime().toInstant().atZone(QC_REPORT_ZONE).toLocalDate();
        String qcType = r.getQualityControlType();
        Object gen;
        if (ZERO_CHECK.getCode().equals(qcType) || SPAN_CHECK.getCode().equals(qcType)) {
            gen = new GenZeroAndSpanReport(core, day, Collections.singletonList(r));
        } else if (MULTI_CHECK.getCode().equals(qcType)) {
            gen = new GenMultiCheckReport(core, r);
        } else if (PRECISION_CHECK.getCode().equals(qcType)) {
            gen = new GenPrecisionReport(core, r);
        } else if (ACCURACY_CHECK.getCode().equals(qcType)) {
            gen = new GenAccuracyReport(core, r);
        } else if (CONVERSION_CHECK.getCode().equals(qcType)) {
            gen = new GenConversionReport(core, r);
        } else if ("calibration_check".equals(qcType)) {
            gen = new GenTransferAndTraceReport(core, r);
        } else if (AUDIT_SPAN_CHECK.getCode().equals(qcType)) {
            gen = new GenAuditSpanReport(core, Collections.singletonList(r));
        } else {
            throw new IllegalArgumentException("Unsupported quality control type for preview: " + qcType);
        }
        try {
            Object reportBean = gen.getClass().getMethod("getReport").invoke(gen);
            String component = (String) reportBean.getClass().getMethod("getComponent").invoke(reportBean);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) reportBean.getClass().getMethod("getReportData").invoke(reportBean);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("component", component != null ? component : "");
            out.put("reportData", data != null ? data : new HashMap<String, Object>());
            if ("ReportD4".equals(component) && data != null && !data.containsKey("audit_result")) {
                try {
                    Object cal = reportBean.getClass().getMethod("getCalibrationResult").invoke(reportBean);
                    if (cal != null) {
                        data.put("audit_result", String.valueOf(cal));
                    }
                } catch (ReflectiveOperationException ignored) {
                    // ignore
                }
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to assemble report preview", e);
        }
    }

}


////////////////////////////////////// SON CLASSES //////////////////////////////////////
/**
 * GenZeroAndSpanReport
 * <p>生成仪器运行状况检查/校准记录表</p>
 * {@code @description} 生成零点及跨度检测报表
 * 优先实现如下需求:
 *     获取最新的一条零点检查记录 + 跨度检查记录
 *     判断是否进行了校准，均没有校准，则返回空表？ 若进行了零点校准，则将过程及返回值记录在表格中。
 * @author caohongbo
 * @version 1.0
 */
class GenZeroAndSpanReport extends ReportGenerator {

    public SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private EcatCore core;

    private final LocalDate businessDay;

    @Getter
    private ZeroAndSpanReport report;

    public final static String FULL_SPAN = "500ppb";
    public final static String FULL_SPAN_CO = "50ppm";

    /**
     * @param businessDay   监管日（与记录 start_time 在同一时区下的日历日一致）
     * @param chosenRecords 择优后的 1～2 条记录（零点、跨度各至多一条）
     */
    public GenZeroAndSpanReport(EcatCore core, LocalDate businessDay, List<EnvQualityControlRecords> chosenRecords) {
        super(core);
        this.core = core;
        this.businessDay = businessDay;
        report = new ZeroAndSpanReport();
        report = parseRecordToReport(chosenRecords);
        report.setComponent(ReportTypeEnum.ZERO_SPAN.getComponent());

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param chosenRecords 择优后的记录列表
     * @return report 质控报告
     */
    private ZeroAndSpanReport parseRecordToReport(List<EnvQualityControlRecords> chosenRecords) {
        EnvQualityControlRecords zero = null;
        EnvQualityControlRecords span = null;
        for (EnvQualityControlRecords r : chosenRecords) {
            if (ZERO_CHECK.getCode().equals(r.getQualityControlType())) {
                zero = r;
            } else if (SPAN_CHECK.getCode().equals(r.getQualityControlType())) {
                span = r;
            }
        }
        EnvQualityControlRecords anchor = zero != null ? zero : span;
        if (anchor == null) {
            throw new IllegalArgumentException("Zero/span report requires at least one record");
        }

        report.setReportDate(Date.from(businessDay.atStartOfDay(ReportGenerator.QC_REPORT_ZONE).toInstant()));
        String filerRef = firstNonBlank(recordCreatorRef(anchor), recordCreatorRef(zero), recordCreatorRef(span));
        String reviewerRef = firstNonBlank(recordUpdaterRef(anchor), recordUpdaterRef(span), recordUpdaterRef(zero), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? anchor.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(anchor.getUpdatedBy(), anchor.getUpdateBy()) : reviewerRef);
        report.setGasType(anchor.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        if (param == null) {
            logger.warn("Zero/span report: unknown parameter code {}, skip instrument / std gas resolution", report.getGasType());
        }
        report.setGasSource(param != null ? param : "");
        if (param != null) {
            applyStandardGasSourceAndNoFromLogicDevices(report, param);
            refreshReportGasSourceAndNoCombined(report);
        }
        DeviceBase device = param != null ? getDeviceInfo(param) : null;
        if (device != null) {
            report.setInstrumentName(device.getName() != null ? device.getName() : "");
            report.setReportName(device.getName() + report.getReportName());
            report.setInstrumentNo(device.getSn() != null ? device.getSn() : "");
            report.setInstrumentNameAndNo(
                    (device.getName() != null ? device.getName() : "") + (device.getSn() != null ? device.getSn() : ""));
        } else {
            report.setInstrumentName("");
            report.setReportName(report.getReportName());
            report.setInstrumentNo("");
            report.setInstrumentNameAndNo("");
        }
        String gasConcentration = param != null ? getStdGasConcentration(param) : "";
        report.setGasConcentration(gasConcentration == null ? "" : gasConcentration);

        if (zero != null) {
            report.setZeroStartTime(fmtTime(zero.getStartTime()));
            report.setZeroEndTime(fmtTime(zero.getEndTime() != null ? zero.getEndTime() : zero.getStartTime()));
            Map<String, Object> executionLogMap = QualityControlExecutionLogHelper.metricsForReport(zero.getExecutionLog());
            applyZeroMetrics(executionLogMap, zero.getParameter());
            boolean zp = QualityControlExecutionLogHelper.readBoolean(executionLogMap, "isPass");
            report.setZeroCalibrationResult(zp ? "合格" : "不合格");
        }
        if (span != null) {
            report.setSpan80StartTime(fmtTime(span.getStartTime()));
            report.setSpan80EndTime(fmtTime(span.getEndTime() != null ? span.getEndTime() : span.getStartTime()));
            Map<String, Object> resultEvaluation = QualityControlExecutionLogHelper.metricsForReport(span.getExecutionLog());
            applySpanMetrics(resultEvaluation, span.getParameter());
            boolean sp = QualityControlExecutionLogHelper.readBoolean(resultEvaluation, "isPass");
            report.setSpanCalibrationResult(sp ? "合格" : "不合格");
        }
        report.setReportNote(buildZeroSpanReportRemark(zero, span));

        Date kpStart = null;
        Date kpEnd = null;
        for (EnvQualityControlRecords r : new EnvQualityControlRecords[]{zero, span}) {
            if (r == null) {
                continue;
            }
            Date s = r.getStartTime();
            Date e = r.getEndTime() != null ? r.getEndTime() : r.getStartTime();
            if (s != null && (kpStart == null || s.before(kpStart))) {
                kpStart = s;
            }
            if (e != null && (kpEnd == null || e.after(kpEnd))) {
                kpEnd = e;
            }
        }
        if (kpStart == null) {
            kpStart = anchor.getStartTime();
        }
        if (kpEnd == null) {
            kpEnd = anchor.getEndTime() != null ? anchor.getEndTime() : anchor.getStartTime();
        }
        Date[] readWin = firstReadPhaseWindowForKeyParameters(zero, span);
        if (readWin != null) {
            kpStart = readWin[0];
            kpEnd = readWin[1];
        }
        List<Map<String, Object>> keyParameters = ReportGenerator.mergeKeyParameterSnapshotsFromRecords(zero, span);
        if (keyParameters.isEmpty() && param != null && device != null) {
            keyParameters = queryKeyParameters(kpStart, kpEnd, param, device.getId());
        }
        report.setKeyParameters(keyParameters);
        enrichKeyParameterRowsForReport(report.getKeyParameters(), report.getGasType());
        report.setZeroCalibrationValueApplicable(
                zero != null && QualityControlExecutionLogHelper.hasCompletedCalibrationPhase(zero.getExecutionLog()));
        report.setSpanCalibrationValueApplicable(
                span != null && QualityControlExecutionLogHelper.hasCompletedCalibrationPhase(span.getExecutionLog()));

        report.setQcPhaseTimelinesForReport(mergeQcPhaseTimelines(zero, span));

        return report;
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> mergeQcPhaseTimelines(EnvQualityControlRecords... records) {
        List<Map<String, Object>> merged = new ArrayList<>();
        if (records == null) {
            return merged;
        }
        for (EnvQualityControlRecords r : records) {
            if (r == null || r.getExecutionLog() == null || r.getExecutionLog().trim().isEmpty()) {
                continue;
            }
            Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(r.getExecutionLog());
            Object raw = root.get(QualityControlExecutionLogHelper.QC_PHASE_TIMELINES_KEY);
            if (!(raw instanceof List)) {
                continue;
            }
            String tag = ZERO_CHECK.getCode().equals(r.getQualityControlType())
                    ? "零点"
                    : (SPAN_CHECK.getCode().equals(r.getQualityControlType()) ? "跨度" : "记录");
            for (Object o : (List<?>) raw) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) o);
                row.put("recordTag", tag);
                merged.add(row);
            }
        }
        return merged;
    }

    private String fmtTime(Date d) {
        return d != null ? sdf.format(d) : "";
    }

    private void applyZeroMetrics(Map<String, Object> executionLogMap, String parameterCode) {
        if (executionLogMap == null || executionLogMap.isEmpty()) {
            return;
        }
        Object calSrc = pickVerificationOrStd(executionLogMap);
        if ("4".equals(parameterCode)) {
            try {
                report.setZeroStandardConcentration(((Number) executionLogMap.get("stdValue")).doubleValue() / 1000 + "");
                report.setZeroDisplayResponse(((Number) executionLogMap.get("deviceValue")).doubleValue() / 1000 + "");
                report.setZeroCalibrationResponse(
                        calSrc instanceof Number ? (((Number) calSrc).doubleValue() / 1000 + "") : String.valueOf(calSrc));
                report.setZeroDriftResult(((Number) executionLogMap.get("resultValue")).doubleValue() / 1000 + "");
            } catch (Exception e) {
                report.setZeroStandardConcentration(String.valueOf(executionLogMap.get("stdValue")));
                report.setZeroDisplayResponse(String.valueOf(executionLogMap.get("deviceValue")));
                report.setZeroCalibrationResponse(calSrc != null ? String.valueOf(calSrc) : String.valueOf(executionLogMap.get("stdValue")));
                report.setZeroDriftResult(String.valueOf(executionLogMap.get("resultValue")));
            }
        } else {
            report.setZeroStandardConcentration(String.valueOf(executionLogMap.get("stdValue")));
            report.setZeroDisplayResponse(String.valueOf(executionLogMap.get("deviceValue")));
            report.setZeroCalibrationResponse(calSrc != null ? String.valueOf(calSrc) : String.valueOf(executionLogMap.get("stdValue")));
            report.setZeroDriftResult(String.valueOf(executionLogMap.get("resultValue")));
        }
    }

    private void applySpanMetrics(Map<String, Object> resultEvaluation, String parameterCode) {
        if (resultEvaluation == null || resultEvaluation.isEmpty()) {
            return;
        }
        report.setSpan80DriftResult(String.valueOf(resultEvaluation.get("resultValue")));
        Object calSrc = pickVerificationOrStd(resultEvaluation);
        if ("4".equals(parameterCode)) {
            try {
                report.setSpan80StandardConcentration(((Number) resultEvaluation.get("stdValue")).doubleValue() / 1000 + "");
                report.setSpan80DisplayResponse(((Number) resultEvaluation.get("deviceValue")).doubleValue() / 1000 + "");
                report.setSpan80CalibrationResponse(
                        calSrc instanceof Number ? (((Number) calSrc).doubleValue() / 1000 + "") : String.valueOf(calSrc));
            } catch (Exception e) {
                report.setSpan80StandardConcentration(String.valueOf(resultEvaluation.get("stdValue")));
                report.setSpan80DisplayResponse(String.valueOf(resultEvaluation.get("deviceValue")));
                report.setSpan80CalibrationResponse(calSrc != null ? String.valueOf(calSrc) : String.valueOf(resultEvaluation.get("stdValue")));
            }
        } else {
            report.setSpan80StandardConcentration(String.valueOf(resultEvaluation.get("stdValue")));
            report.setSpan80DisplayResponse(String.valueOf(resultEvaluation.get("deviceValue")));
            report.setSpan80CalibrationResponse(calSrc != null ? String.valueOf(calSrc) : String.valueOf(resultEvaluation.get("stdValue")));
        }
    }
    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();

        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrument_info = new HashMap<>();
        // 设备名称及编号
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        // 校准日期
        Date reportDate = report.getReportDate();
        instrument_info.put("report_date", reportDate != null ? new SimpleDateFormat("yyyy-MM-dd").format(reportDate) : "");
        // 标气来源及编号
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        // 标气浓度
        instrument_info.put("gas_concentration", appendConcUnit(report.getGasConcentration(), report.getGasType()));
        boolean hasZero = report.getZeroStartTime() != null && !report.getZeroStartTime().trim().isEmpty();
        boolean hasSpan = report.getSpan80StartTime() != null && !report.getSpan80StartTime().trim().isEmpty();
        boolean passOverall = true;
        if (hasZero) {
            passOverall &= "合格".equals(report.getZeroCalibrationResult());
        }
        if (hasSpan) {
            passOverall &= "合格".equals(report.getSpanCalibrationResult());
        }
        instrument_info.put("qc_stamp", passOverall ? "pass" : "fail");
        reportContent.put("instrument_info", instrument_info);
        // 零点和跨度结果
        List<Map<String, Object>> calibrationPoints = new ArrayList<>();
        calibrationPoints.add(
                new HashMap<String, Object>() {{
                    put("point_name", "零点");
                    put("start_time", report.getZeroStartTime());
                    put("end_time", report.getZeroEndTime());
                    put("standard_concentration", appendConcUnit(report.getZeroStandardConcentration(), report.getGasType()));
                    put("display_value", appendConcUnit(report.getZeroDisplayResponse(), report.getGasType()));
                    put("calibration_value", report.isZeroCalibrationValueApplicable()
                            ? appendConcUnit(report.getZeroCalibrationResponse(), report.getGasType()) : "");
                }}
        );
        calibrationPoints.add(
                new HashMap<String, Object>() {{
                    put("point_name", "满量程的80%");
                    put("start_time", report.getSpan80StartTime());
                    put("end_time", report.getSpan80EndTime());
                    put("standard_concentration", appendConcUnit(report.getSpan80StandardConcentration(), report.getGasType()));
                    put("display_value", appendConcUnit(report.getSpan80DisplayResponse(), report.getGasType()));
                    put("calibration_value", report.isSpanCalibrationValueApplicable()
                            ? appendConcUnit(report.getSpan80CalibrationResponse(), report.getGasType()) : "");
                }}
        );
        reportContent.put("calibration_points", calibrationPoints);
        String gasCode = report.getGasType();
        String fullSpan = CO.getCode().equals(gasCode) ? FULL_SPAN_CO : FULL_SPAN;
        reportContent.put("full_span", fullSpan);
        String driftUnit = CO.getCode().equals(gasCode) ? " ppm" : " ppb";
        reportContent.put("zero_drift_result", appendDriftUnit(report.getZeroDriftResult(), driftUnit));
        reportContent.put("span_80_drift_result", formatSpanDriftPercentDisplay(report.getSpan80DriftResult()));
        reportContent.put("key_parameters", report.getKeyParameters());
        reportContent.put("span_calibration_result", report.getSpanCalibrationResult());
        reportContent.put("zero_calibration_result", report.getZeroCalibrationResult());
        reportContent.put("qc_phase_timelines", report.getQcPhaseTimelinesForReport());
        reportContent.put("remark", report.getReportNote() != null ? report.getReportNote() : "");
        reportContent.put("filler", report.getFiler() != null ? report.getFiler() : "");
        reportContent.put("reviewer", report.getReviewer() != null ? report.getReviewer() : "");

        return reportContent;
    }
}

/**
 * GenMultiCheckReport
 * <p>生成仪器多点校准记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenMultiCheckReport extends ReportGenerator {

    private EcatCore core;

    @Getter
    private MultiCheckReport report;

    private EnvQualityControlRecords record;

    public GenMultiCheckReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        this.record = record;
        report = new MultiCheckReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.MULTI.getComponent());

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private MultiCheckReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime());
        String filerRef = recordCreatorRef(record);
        String reviewerRef = firstNonBlank(recordUpdaterRef(record), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? record.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(record.getUpdatedBy(), record.getUpdateBy()) : reviewerRef);
        report.setReportNote(record.getResultEvaluation());
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param != null ? param : "");
        if (param != null) {
            applyStandardGasSourceAndNoFromLogicDevices(report, param);
            refreshReportGasSourceAndNoCombined(report);
        }
        DeviceBase device = param != null ? getDeviceInfo(param) : null;
        if (device != null) {
            report.setInstrumentName(device.getName() != null ? device.getName() : "");
            report.setInstrumentNo(device.getSn() != null ? device.getSn() : "");
            report.setInstrumentNameAndNo(
                    (device.getName() != null ? device.getName() : "") + (device.getSn() != null ? device.getSn() : ""));
            report.setReportName(device.getName() + report.getReportName());
        } else {
            report.setInstrumentName("");
            report.setInstrumentNo("");
            report.setInstrumentNameAndNo("");
            report.setReportName(report.getReportName());
        }
        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = QualityControlExecutionLogHelper.metricsForReport(executionLog);
        report.setFormula("Y = aX + b");
        report.setA(metricString(executionLogMap, "slope"));
        report.setB(metricString(executionLogMap, "intercept"));
        report.setR(metricString(executionLogMap, "correlation"));

        List<Float> gasConcentrationInput = convertToFloatList(executionLogMap.get("stdValues"));
        report.setGasConcentrationsInput(gasConcentrationInput);
        List<Float> instrumentResponse = convertToFloatList(executionLogMap.get("deviceValues"));
        report.setInstrumentResponses(instrumentResponse);

        String cylinderConc = param != null ? getStdGasConcentration(param) : "";
        report.setGasConcentration(cylinderConc == null ? "" : cylinderConc);

        boolean pass = QualityControlExecutionLogHelper.readBoolean(executionLogMap, "isPass");
        report.setCalibrationResult(pass ? "合格" : "不合格");

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        String gasCode = report.getGasType();
        List<String> conc = formatFloatListWithConcUnit(report.getGasConcentrationsInput(), gasCode);
        List<String> resp = formatFloatListWithConcUnit(report.getInstrumentResponses(), gasCode);
        int n = Math.max(conc.size(), resp.size());
        if (n == 0) {
            n = 1;
        }
        int totalCols = Math.max(7, n + 1);
        int dataCols = totalCols - 1;
        while (conc.size() < dataCols) {
            conc.add("");
        }
        while (resp.size() < dataCols) {
            resp.add("");
        }
        reportContent.put("table_total_column_count", totalCols);
        reportContent.put("instrument_meta_colspan", Math.max(1, totalCols - 4));

        Map<String, String> instrument_info = new HashMap<>();
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        instrument_info.put("gas_concentration", appendConcUnit(stripNumericConcentration(report.getGasConcentration()), gasCode));
        boolean pass = record != null && QualityControlExecutionLogHelper.readIsPass(record.getExecutionLog());
        instrument_info.put("qc_stamp", pass ? "pass" : "fail");
        reportContent.put("instrument_info", instrument_info);

        reportContent.put("gas_concentrations_input", conc);
        reportContent.put("instrument_responses", resp);

        Map<String, String> calibration_curve = new HashMap<>();
        calibration_curve.put("formula", report.getFormula());
        calibration_curve.put("a", report.getA());
        calibration_curve.put("b", report.getB());
        calibration_curve.put("r", report.getR());
        reportContent.put("calibration_curve", calibration_curve);
        reportContent.put("calibration_result", report.getCalibrationResult());
        reportContent.put("audit_result", report.getCalibrationResult());
        reportContent.put("remark", report.getReportNote() != null ? report.getReportNote() : "");
        reportContent.put("filer", report.getFiler() != null ? report.getFiler() : "");
        reportContent.put("reviewer", report.getReviewer() != null ? report.getReviewer() : "");

        return reportContent;
    }
}

/**
 * GenPrecisionReport
 * <p>生成仪器精密度审核记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenPrecisionReport extends ReportGenerator {

    private EcatCore core;

    private final EnvQualityControlRecords qcRecord;

    @Getter
    private PrecisionReport report;

    public GenPrecisionReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        this.qcRecord = record;
        report = new PrecisionReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.PRECISION.getComponent());

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);

    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private PrecisionReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime());
        String filerRef = recordCreatorRef(record);
        String reviewerRef = firstNonBlank(recordUpdaterRef(record), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? record.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(record.getUpdatedBy(), record.getUpdateBy()) : reviewerRef);
        report.setReportNote(record.getResultEvaluation());
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param != null ? param : "");
        if (param != null) {
            applyStandardGasSourceAndNoFromLogicDevices(report, param);
            refreshReportGasSourceAndNoCombined(report);
        }
        DeviceBase device = param != null ? getDeviceInfo(param) : null;
        if (device != null) {
            report.setInstrumentName(device.getName() != null ? device.getName() : "");
            report.setInstrumentNo(device.getSn() != null ? device.getSn() : "");
            report.setInstrumentNameAndNo(
                    (device.getName() != null ? device.getName() : "") + (device.getSn() != null ? device.getSn() : ""));
            report.setReportName(device.getName() + report.getReportName());
        } else {
            report.setInstrumentName("");
            report.setInstrumentNo("");
            report.setInstrumentNameAndNo("");
            report.setReportName(report.getReportName());
        }
        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = QualityControlExecutionLogHelper.metricsForReport(executionLog);
        Float precision = convertToFloat(executionLogMap.get("precision"));
        Float devicesStdGas = convertToFloat(executionLogMap.get("devicesStdGas"));
        report.setDevicesStdGasFromMetrics(devicesStdGas);
        List<Float> instrumentResponse = convertToFloatList(executionLogMap.get("deviceValues"));
        report.setInstrumentResponses(instrumentResponse);
        report.setRelativeStandardDeviation(precision);

        String cylinderConc = param != null ? getStdGasConcentration(param) : "";
        report.setGasConcentration(cylinderConc == null ? "" : cylinderConc);

        boolean pass = QualityControlExecutionLogHelper.readBoolean(executionLogMap, "isPass");
        report.setCalibrationResult(pass ? "合格" : "不合格");

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrument_info = new HashMap<>();
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        instrument_info.put("gas_concentration", appendConcUnit(stripNumericConcentration(report.getGasConcentration()), report.getGasType()));
        boolean pass = qcRecord != null && QualityControlExecutionLogHelper.readIsPass(qcRecord.getExecutionLog());
        instrument_info.put("qc_stamp", pass ? "pass" : "fail");
        reportContent.put("instrument_info", instrument_info);

        String gasCode = report.getGasType();
        List<Float> resp = report.getInstrumentResponses();
        List<String> concCells = new ArrayList<>();
        if (resp != null) {
            for (int i = 0; i < resp.size(); i++) {
                String rawNum = report.getDevicesStdGasFromMetrics() != null
                        ? String.valueOf(report.getDevicesStdGasFromMetrics())
                        : stripNumericConcentration(report.getGasConcentration());
                concCells.add(appendConcUnit(rawNum, gasCode));
            }
        }
        reportContent.put("gas_concentrations_input", concCells);
        reportContent.put("instrument_responses", formatFloatListWithConcUnit(resp, gasCode));
        reportContent.put("relative_standard_deviation", formatRelativeStandardDeviationPercent(report.getRelativeStandardDeviation()));
        reportContent.put("audit_result", report.getCalibrationResult());
        reportContent.put("remark", report.getReportNote() != null ? report.getReportNote() : "");
        reportContent.put("filer", report.getFiler() != null ? report.getFiler() : "");
        reportContent.put("reviewer", report.getReviewer() != null ? report.getReviewer() : "");

        return reportContent;

    }
}

/**
 * GenAccuracyReport
 * <p>生成仪器准确度审核记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenAccuracyReport extends ReportGenerator {

    private EcatCore core;

    private final EnvQualityControlRecords qcRecord;

    @Getter
    private AccuracyReport report;

    public GenAccuracyReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        this.qcRecord = record;
        report = new AccuracyReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.ACCURACY.getComponent());

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);

    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private AccuracyReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime());
        String filerRef = recordCreatorRef(record);
        String reviewerRef = firstNonBlank(recordUpdaterRef(record), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? record.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(record.getUpdatedBy(), record.getUpdateBy()) : reviewerRef);
        report.setReportNote(record.getResultEvaluation());
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param != null ? param : "");
        if (param != null) {
            applyStandardGasSourceAndNoFromLogicDevices(report, param);
            refreshReportGasSourceAndNoCombined(report);
        }
        DeviceBase device = param != null ? getDeviceInfo(param) : null;
        if (device != null) {
            report.setInstrumentName(device.getName() != null ? device.getName() : "");
            report.setInstrumentNo(device.getSn() != null ? device.getSn() : "");
            report.setInstrumentNameAndNo(
                    (device.getName() != null ? device.getName() : "") + (device.getSn() != null ? device.getSn() : ""));
            report.setReportName(device.getName() + report.getReportName());
        } else {
            report.setInstrumentName("");
            report.setInstrumentNo("");
            report.setInstrumentNameAndNo("");
            report.setReportName(report.getReportName());
        }
        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = QualityControlExecutionLogHelper.metricsForReport(executionLog);
        report.setFormula("Y = aX + b");
        report.setA(metricString(executionLogMap, "slope"));
        report.setB(metricString(executionLogMap, "intercept"));
        report.setR(metricString(executionLogMap, "correlation"));
        report.setAverageRelativeError(metricString(executionLogMap, "relativeError"));

        List<Float> gasConcentrationInput = convertToFloatList(executionLogMap.get("stdValues"));
        report.setGasConcentrationsInput(gasConcentrationInput);
        List<Float> instrumentResponse = convertToFloatList(executionLogMap.get("deviceValues"));
        report.setInstrumentResponses(instrumentResponse);

        String cylinderConc = param != null ? getStdGasConcentration(param) : "";
        report.setGasConcentration(cylinderConc == null ? "" : cylinderConc);

        boolean pass = QualityControlExecutionLogHelper.readBoolean(executionLogMap, "isPass");
        report.setCalibrationResult(pass ? "合格" : "不合格");

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        String gasCode = report.getGasType();
        Map<String, String> instrument_info = new HashMap<>();
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        instrument_info.put("gas_concentration", appendConcUnit(stripNumericConcentration(report.getGasConcentration()), gasCode));
        boolean pass = qcRecord != null && QualityControlExecutionLogHelper.readIsPass(qcRecord.getExecutionLog());
        instrument_info.put("qc_stamp", pass ? "pass" : "fail");
        reportContent.put("instrument_info", instrument_info);

        reportContent.put("gas_concentrations_input", formatFloatListWithConcUnit(report.getGasConcentrationsInput(), gasCode));
        reportContent.put("instrument_responses", formatFloatListWithConcUnit(report.getInstrumentResponses(), gasCode));

        Map<String, String> calibration_curve = new HashMap<>();
        calibration_curve.put("formula", report.getFormula());
        calibration_curve.put("a", report.getA());
        calibration_curve.put("b", report.getB());
        calibration_curve.put("r", report.getR());
        reportContent.put("calibration_curve", calibration_curve);

        Float relF = convertToFloat(report.getAverageRelativeError());
        String relDisp = relF != null
                ? formatRelativeStandardDeviationPercent(relF)
                : ensurePercentSuffix(report.getAverageRelativeError());
        reportContent.put("average_relative_error", relDisp);
        reportContent.put("calibration_result", report.getCalibrationResult());
        reportContent.put("audit_result", report.getCalibrationResult());
        reportContent.put("remark", report.getReportNote() != null ? report.getReportNote() : "");
        reportContent.put("filer", report.getFiler() != null ? report.getFiler() : "");
        reportContent.put("reviewer", report.getReviewer() != null ? report.getReviewer() : "");

        return reportContent;
    }

}

/**
 * GenConversionReport
 * <p>生成氮氧化物转换效率测试记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenConversionReport extends ReportGenerator {

    private EcatCore core;

    private final EnvQualityControlRecords qcRecord;

    @Getter
    private ConversionReport report;

    public GenConversionReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        this.qcRecord = record;
        report = new ConversionReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.CONVERSION.getComponent());

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);

    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private ConversionReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime()); // 报告日期 默认是质控记录开始时间
        String filerRef = recordCreatorRef(record);
        String reviewerRef = firstNonBlank(recordUpdaterRef(record), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? record.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(record.getUpdatedBy(), record.getUpdateBy()) : reviewerRef);
        report.setReportNote(record.getResultEvaluation()); // 备注 默认是质控记录结果评价
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param != null ? param : "");
        if (param != null) {
            applyStandardGasSourceAndNoFromLogicDevices(report, param);
            refreshReportGasSourceAndNoCombined(report);
        }
        DeviceBase device = param != null ? getDeviceInfo(param) : null;
        if (device != null) {
            report.setInstrumentName(device.getName() != null ? device.getName() : "");
            report.setInstrumentNo(device.getSn() != null ? device.getSn() : "");
            report.setInstrumentNameAndNo(
                    (device.getName() != null ? device.getName() : "") + (device.getSn() != null ? device.getSn() : ""));
            report.setReportName(device.getName() + report.getReportName());
        } else {
            report.setInstrumentName("");
            report.setInstrumentNo("");
            report.setInstrumentNameAndNo("");
            report.setReportName(report.getReportName());
        }

        String concUnitCode = ParameterEnum.NO2.getCode();
        List<String> gasConcentrations = new ArrayList<>(2);
        String noC = getStdGasConcentration("NO");
        String no2C = getStdGasConcentration("NO2");
        gasConcentrations.add(noC == null ? "" : appendConcUnit(stripNumericConcentration(noC), concUnitCode));
        gasConcentrations.add(no2C == null ? "" : appendConcUnit(stripNumericConcentration(no2C), concUnitCode));
        report.setGasConcentration(gasConcentrations);

        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = QualityControlExecutionLogHelper.metricsForReport(executionLog);

        report.setOrigNo2Datas(convertToFloatList(firstNonNullMetric(executionLogMap, "origNo2Datas", "remNo2Datas")));
        report.setOrigNo2Avg(convertToFloat(firstNonNullMetric(executionLogMap, "origNo2Avg", "remNo2Avg")));
        report.setNo2Efficiency(convertToFloat(firstNonNullMetric(executionLogMap, "no2Efficiency", "efficiency2")));

        report.setRemNoDatas(convertToFloatList(firstNonNullMetric(executionLogMap, "remNoDatas")));
        report.setRemNoxDatas(convertToFloatList(firstNonNullMetric(executionLogMap, "remNoxDatas")));
        report.setOrigNoDatas(convertToFloatList(firstNonNullMetric(executionLogMap, "origNoDatas")));
        report.setOrigNoxDatas(convertToFloatList(firstNonNullMetric(executionLogMap, "origNoxDatas")));
        report.setRemNoAvg(convertToFloat(firstNonNullMetric(executionLogMap, "remNoAvg")));
        report.setRemNoxAvg(convertToFloat(firstNonNullMetric(executionLogMap, "remNoxAvg")));
        report.setOrigNoAvg(convertToFloat(firstNonNullMetric(executionLogMap, "origNoAvg")));
        report.setOrigNoxAvg(convertToFloat(firstNonNullMetric(executionLogMap, "origNoxAvg")));

        Object effObj = firstNonNullMetric(executionLogMap, "efficiency", "conversionEfficiency");
        /* NO 路径转换效率：与入库 execution_log.result 中主字段一致（编排器写入后原样持久化）。 */
        report.setNoEfficiency(effObj == null ? "" : String.valueOf(effObj));

        boolean pass = QualityControlExecutionLogHelper.readBoolean(executionLogMap, "isPass");
        report.setCalibrationResult(pass ? "合格" : "不合格");

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        boolean pass = qcRecord != null && QualityControlExecutionLogHelper.readIsPass(qcRecord.getExecutionLog());
        reportContent.put("is_pass", pass);
        reportContent.put("calibration_result", report.getCalibrationResult());

        String concUnitCode = ParameterEnum.NO2.getCode();

        Map<String, String> instrument_info = new HashMap<>();
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        instrument_info.put("qc_stamp", pass ? "pass" : "fail");
        if (report.getGasConcentration() != null && report.getGasConcentration().size() > 1) {
            instrument_info.put("no_concentration", report.getGasConcentration().get(0));
            instrument_info.put("no2_concentration", report.getGasConcentration().get(1));
        } else if (report.getGasConcentration() != null && report.getGasConcentration().size() > 0) {
            instrument_info.put("no_concentration", report.getGasConcentration().get(0));
            instrument_info.put("no2_concentration", "");
        } else {
            instrument_info.put("no_concentration", "");
            instrument_info.put("no2_concentration", "");
        }
        reportContent.put("instrument_info", instrument_info);

        reportContent.put("orig_no2_datas", formatFloatListWithConcUnit(report.getOrigNo2Datas(), concUnitCode));
        reportContent.put("orig_no2_avg", formatSingleConcForReport(report.getOrigNo2Avg(), concUnitCode));
        reportContent.put("no2_efficiency", formatEfficiencyPercent(report.getNo2Efficiency()));

        reportContent.put("rem_no_datas", formatFloatListWithConcUnit(report.getRemNoDatas(), concUnitCode));
        reportContent.put("rem_no_avg", formatSingleConcForReport(report.getRemNoAvg(), concUnitCode));
        reportContent.put("rem_nox_datas", formatFloatListWithConcUnit(report.getRemNoxDatas(), concUnitCode));
        reportContent.put("rem_nox_avg", formatSingleConcForReport(report.getRemNoxAvg(), concUnitCode));
        reportContent.put("orig_no_datas", formatFloatListWithConcUnit(report.getOrigNoDatas(), concUnitCode));
        reportContent.put("orig_no_avg", formatSingleConcForReport(report.getOrigNoAvg(), concUnitCode));
        reportContent.put("orig_nox_datas", formatFloatListWithConcUnit(report.getOrigNoxDatas(), concUnitCode));
        reportContent.put("orig_nox_avg", formatSingleConcForReport(report.getOrigNoxAvg(), concUnitCode));
        reportContent.put("efficiency", ensurePercentSuffix(report.getNoEfficiency()));
        reportContent.put("result_evaluation", report.getReportNote() != null ? report.getReportNote() : "");
        reportContent.put("filer", report.getFiler() != null ? report.getFiler() : "");
        reportContent.put("reviewer", report.getReviewer() != null ? report.getReviewer() : "");

        return reportContent;
    }

    private static String formatSingleConcForReport(Float f, String gasTypeCode) {
        if (f == null) {
            return "";
        }
        return appendConcUnit(String.valueOf(f), gasTypeCode);
    }
}

/**
 * GenTransferAndTraceReport
 * <p>生成臭氧校准设备量值传递记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenTransferAndTraceReport extends ReportGenerator {

    private EcatCore core;

    @Getter
    private TransferAndTraceReport report;

    public GenTransferAndTraceReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        report = new TransferAndTraceReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.CALIBRATION.getComponent());

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);

    }

    /**
     * 解析质控记录中的用于报告的数据
     *
     * @param record 质控记录
     * @return report 质控报告
     */
    private TransferAndTraceReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime()); // 报告日期 默认是质控记录开始时间
        String filerRef = recordCreatorRef(record);
        String reviewerRef = firstNonBlank(recordUpdaterRef(record), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? record.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(record.getUpdatedBy(), record.getUpdateBy()) : reviewerRef);
        report.setReportNote(record.getResultEvaluation()); // 备注 默认是质控记录结果评价
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);
        applyStandardGasSourceAndNoFromLogicDevices(report, param);
        refreshReportGasSourceAndNoCombined(report);
        DeviceBase device = getDeviceInfo(param);
        report.setInstrumentName(device.getName());
        report.setInstrumentNo(device.getSn());
        report.setInstrumentNameAndNo(device.getName() + device.getSn());
        report.setReportName(device.getName() + report.getReportName());

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());

        return reportContent;
    }

}

/**
 * GenAuditSpanReport
 * <p>生成人工零跨核查记录表</p>
 * @version 1.0
 */
class GenAuditSpanReport extends ReportGenerator {

    public SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private EcatCore core;

    @Getter
    private AuditSpanReport report;

    public final static String FULL_SPAN = "500ppb";
    public final static String FULL_SPAN_CO = "50ppm";

    /**
     * 实际执行此方法 生成报告
     * @param records
     */
    public GenAuditSpanReport(EcatCore core, List<EnvQualityControlRecords> records) {
        super(core);
        this.core = core;
        report = new AuditSpanReport();
        report = parseRecordToReport(records);
        report.setComponent(ReportTypeEnum.AUDIT_SPAN.getComponent());

        // 用于报告展示的主要内容
        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param records 质控记录
     * @return report 质控报告
     */
    private AuditSpanReport parseRecordToReport(List<EnvQualityControlRecords> records) {
        EnvQualityControlRecords records_0 = records.get(0);

        report.setReportDate(records_0.getCreateTime());
        String filerRef = recordCreatorRef(records_0);
        String reviewerRef = firstNonBlank(recordUpdaterRef(records_0), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? records_0.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(records_0.getUpdatedBy(), records_0.getUpdateBy()) : reviewerRef);
        report.setGasType(records_0.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);
        applyStandardGasSourceAndNoFromLogicDevices(report, param);
        refreshReportGasSourceAndNoCombined(report);
        DeviceBase device = param != null ? getDeviceInfo(param) : null;
        if (device != null) {
            report.setInstrumentName(device.getName() != null ? device.getName() : "");
            report.setInstrumentNo(device.getSn() != null ? device.getSn() : "");
            report.setInstrumentNameAndNo(
                    (device.getName() != null ? device.getName() : "") + (device.getSn() != null ? device.getSn() : ""));
            report.setReportName(device.getName() + report.getReportName());
        } else {
            report.setInstrumentName("");
            report.setInstrumentNo("");
            report.setInstrumentNameAndNo("");
            report.setReportName(report.getReportName());
        }
        String gasConcentration = param != null ? getStdGasConcentration(param) : "";
        report.setGasConcentration(gasConcentration == null ? "" : gasConcentration);

        List<String> auditRemarkLines = new ArrayList<>();
        boolean coByParameter = CO.getCode().equals(report.getGasType());
        for (EnvQualityControlRecords record : records) {
            if (record.getExecutionLog() != null) {
                Map<String, Object> rootQuick = QualityControlExecutionLogHelper.parseRootMap(record.getExecutionLog());
                String ar = QualityControlExecutionLogHelper.remarkSummaryForFailedQC(record.getExecutionLog(), "人工核查");
                if (ar.isEmpty() && !rootQuick.isEmpty() && !QualityControlExecutionLogHelper.readIsPass(record.getExecutionLog())) {
                    ar = plainEvaluationIfNotJson(record.getResultEvaluation());
                }
                if (!ar.isEmpty()) {
                    auditRemarkLines.add(ar);
                }
            }
            if (!AUDIT_SPAN_CHECK.getCode().equals(record.getQualityControlType())) {
                throw new RuntimeException("Invalid report type for AuditSpanReport: " + record.getQualityControlType());
            }
            report.setSpanStartTime(sdf.format(record.getStartTime()));
            report.setSpanEndTime(sdf.format(record.getEndTime()));

            Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(record.getExecutionLog());
            if (root.isEmpty()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> params = root.get("params") instanceof Map
                    ? (Map<String, Object>) root.get("params")
                    : Collections.emptyMap();
            Number genGasConc = toNumber(params.get("genGasConc"));
            String checkDisplay = formatAuditSetpointDisplay(genGasConc, coByParameter);
            report.setCheckConcentration(checkDisplay);
            report.setSpanStandardConcentration(checkDisplay);

            Object resObj = root.get("result");
            if (resObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> resultList = (List<Map<String, String>>) resObj;
                report.setSpanDisplayResponses(resultList);
            } else {
                report.setSpanDisplayResponses(new ArrayList<>());
            }
            report.setSpanCalibrationResponse(checkDisplay);
        }
        report.setReportNote(String.join("\n", auditRemarkLines));

        List<Map<String, Object>> keyParameters = new ArrayList<>();
        appendKeySnapshotRows(keyParameters, records_0);
        if (keyParameters.isEmpty() && param != null && device != null) {
            Date[] readWin = QualityControlExecutionLogHelper.resolveReadPhaseWindowForKeyParameters(records_0.getExecutionLog());
            Date kpStart = readWin != null ? readWin[0] : records_0.getStartTime();
            Date kpEnd = readWin != null ? readWin[1]
                    : (records_0.getEndTime() != null ? records_0.getEndTime() : records_0.getStartTime());
            keyParameters = queryKeyParameters(kpStart, kpEnd, param, device.getId());
        }
        report.setKeyParameters(keyParameters);
        enrichKeyParameterRowsForReport(report.getKeyParameters(), report.getGasType());

        return report;
    }

    private static Number toNumber(Object o) {
        if (o instanceof Number) {
            return (Number) o;
        }
        if (o == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 任务设定核查浓度：params.genGasConc 按「CO 为 ppm、其余气态为 ppm 设定值→展示为 ppb」与站点任务一致。
     */
    private static String formatAuditSetpointDisplay(Number genGasConc, boolean coByParameterCode) {
        if (genGasConc == null) {
            return "";
        }
        double v = genGasConc.doubleValue();
        if (coByParameterCode) {
            return conciseConcNumber(v) + " ppm";
        }
        return conciseConcNumber(v * 1000.0) + " ppb";
    }

    private static String conciseConcNumber(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "";
        }
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * 核查读数：设备侧多为 ppb；CO 报表换算为 ppm。
     */
    private static String formatAuditInstrumentReadingDisplay(Object rawCheckData, boolean coByParameterCode) {
        if (rawCheckData == null) {
            return "";
        }
        try {
            double v = Double.parseDouble(String.valueOf(rawCheckData).trim());
            if (coByParameterCode) {
                return conciseConcNumber(v / 1000.0) + " ppm";
            }
            return conciseConcNumber(v) + " ppb";
        } catch (Exception e) {
            return String.valueOf(rawCheckData);
        }
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();

        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrument_info = new HashMap<>();
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        instrument_info.put("gas_concentration", appendConcUnit(report.getGasConcentration(), report.getGasType()));
        reportContent.put("instrument_info", instrument_info);
        reportContent.put("start_time", report.getSpanStartTime());
        reportContent.put("end_time", report.getSpanEndTime());

        boolean coByParameter = CO.getCode().equals(report.getGasType());
        List<Map<String, Object>> calibrationPoints = new ArrayList<>();
        for (Map<String, String> auditCheckResultItem : report.getSpanDisplayResponses()) {
            calibrationPoints.add(
                    new HashMap<String, Object>() {{
                        put("point_name", "自定义标点");
                        put("check_concentration", report.getCheckConcentration());
                        put("check_time", auditCheckResultItem.get("checkTime"));
                        put("check_data", formatAuditInstrumentReadingDisplay(auditCheckResultItem.get("checkData"), coByParameter));
                        put("standard_concentration", report.getSpanStandardConcentration());
                        put("calibration_value", "");
                    }}
            );
        }
        reportContent.put("calibration_points", calibrationPoints);
        String gasCode = report.getGasType();
        String fullSpan = CO.getCode().equals(gasCode) ? FULL_SPAN_CO : FULL_SPAN;
        reportContent.put("full_span", fullSpan);
        reportContent.put("span_drift_result", report.getSpanDriftResult());
        reportContent.put("key_parameters", report.getKeyParameters());

        return reportContent;
    }
}
