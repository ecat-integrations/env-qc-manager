package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.core.EcatCore;
import com.ecat.core.LogicDevice.LogicDevice;
import com.ecat.core.LogicDevice.LogicDeviceRegistry;
import com.ecat.core.LogicState.ILogicAttribute;
import com.ecat.core.LogicState.LogicAttributeDefine;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds.EntryId;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds.GasKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 质控报表侧：通过 {@link com.ecat.core.EcatCore#getLogicDeviceRegistry()} 读取逻辑设备属性；
 * 入口 ID 见 {@link com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds}。
 * 关键参数行的属性 ID 来自逻辑设备 {@link LogicDevice#getAttrDefs()} / {@link LogicAttributeDefine#getAttrId()}。
 */
public final class LogicDeviceReportSupport {

    private static final Logger logger = LoggerFactory.getLogger(LogicDeviceReportSupport.class);

    private LogicDeviceReportSupport() {
    }

    private static final class LabeledAttr {
        final String label;
        final String attrId;

        LabeledAttr(String label, String attrId) {
            this.label = label;
            this.attrId = attrId;
        }
    }

    /**
     * 从对应气态分析仪逻辑设备读取「关键参数」：属性 ID 来自该设备
     * {@link LogicDevice#getAttrDefs()}，展示名优先 {@link LogicAttributeDefine#getDisplayName()}。
     *
     * @param parameterName {@link ParameterEnum} 名称：SO2 / NO2 / O3 / CO
     */
    public static List<Map<String, Object>> buildAnalyzerKeyParameters(EcatCore core, String parameterName) {
        List<Map<String, Object>> rows = new ArrayList<>();
        LogicDevice analyzer = resolveGasAnalyzer(core, parameterName);
        if (analyzer == null || analyzer.getAttrMap() == null) {
            logger.warn("无法生成关键参数：分析仪逻辑设备未注册或未就绪 param={}", parameterName);
            return rows;
        }

        List<LabeledAttr> specs = collectAnalyzerKeyParameterSpecs(parameterName, analyzer);
        for (LabeledAttr spec : specs) {
            ILogicAttribute<?> attr = analyzer.getAttrMap().get(spec.attrId);
            LogicAttributeDefine def = findAttrDefine(analyzer, spec.attrId);
            Map<String, Object> row = new HashMap<>();
            row.put("tName", spec.label);
            String display = attr != null ? nullSafe(attr.getDisplayValue()) : "";
            display = appendKeyParameterUnitIfMissing(display, def, parameterName);
            row.put("tValue", display);
            String ref = AnalyzerOperatingStatusNormalRanges.lookupByParameterName(parameterName, spec.label);
            row.put("tRange", ref != null && !ref.isEmpty() ? ref : "");
            row.put("tRemark", "");
            rows.add(row);
        }
        return rows;
    }

    private static LogicAttributeDefine findAttrDefine(LogicDevice analyzer, String attrId) {
        if (analyzer == null || analyzer.getAttrDefs() == null || attrId == null) {
            return null;
        }
        for (LogicAttributeDefine d : analyzer.getAttrDefs()) {
            if (d != null && attrId.equals(d.getAttrId())) {
                return d;
            }
        }
        return null;
    }

    private static String appendKeyParameterUnitIfMissing(String display, LogicAttributeDefine def, String parameterName) {
        if (display == null || display.trim().isEmpty() || def == null) {
            return display == null ? "" : display;
        }
        String d = display.trim();
        if (keyParamValueLooksLikeHasUnit(d)) {
            return d;
        }
        AttributeClass ac = def.getAttrClass();
        if (ac == AttributeClass.FLOW) {
            return d + " L/min";
        }
        if (ac == AttributeClass.PRESSURE) {
            return d + " hPa";
        }
        if (ac == AttributeClass.TEMPERATURE) {
            return d + " °C";
        }
        if (matchesPrimaryGasConcentration(parameterName, ac)) {
            return d + ("CO".equalsIgnoreCase(nullSafe(parameterName).trim()) ? " ppm" : " ppb");
        }
        return d;
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

    /**
     * 钢瓶标气浓度：优先读校准仪逻辑设备上 {@code *_cylinder_concentration}（由 {@link #resolveCalibratorCylinderAttrId} 从定义解析）；
     * O₃ 无钢瓶段时回退标准气逻辑设备上「标气浓度」数值属性（由 {@link #resolveStdGasConcentrationAttrId} 从定义解析）。
     */
    /**
     * 从 {@link EntryId.Station#standardGas(String)} 逻辑设备上按属性展示名启发式读取「标气来源」「标气编号」。
     * <p>匹配规则（不区分大小写）：展示名包含「来源」→来源；包含「编号」或「钢瓶」→编号。
     * 在多个标准气实例上依次尝试，返回首个非空值。</p>
     *
     * @return [0]=来源,[1]=编号；未读到则为空串
     */
    public static String[] tryReadStandardGasSourceAndNo(EcatCore core, String gasLabel) {
        String[] out = new String[] { "", "" };
        if (core == null || gasLabel == null) {
            return out;
        }
        LogicDeviceRegistry reg = core.getLogicDeviceRegistry();
        if (reg == null) {
            return out;
        }
        for (String instance : cylinderInstancesForLabel(gasLabel)) {
            LogicDevice cyl = (LogicDevice) reg.getDeviceByID(EntryId.Station.standardGas(instance));
            if (cyl == null || cyl.getAttrDefs() == null) {
                continue;
            }
            for (LogicAttributeDefine def : cyl.getAttrDefs()) {
                if (def == null || !def.isDisplayable()) {
                    continue;
                }
                String dn = def.getDisplayName();
                if (dn == null || dn.trim().isEmpty()) {
                    continue;
                }
                String low = dn.toLowerCase(Locale.ROOT);
                ILogicAttribute<?> attr = cyl.getAttrMap() != null ? cyl.getAttrMap().get(def.getAttrId()) : null;
                String val = attr != null ? nullSafe(attr.getDisplayValue()) : "";
                if (val.isEmpty()) {
                    continue;
                }
                if (out[0].isEmpty() && (low.contains("来源") || low.contains("source"))) {
                    out[0] = val.trim();
                }
                if (out[1].isEmpty() && (low.contains("编号") || low.contains("no.") || low.contains("钢瓶"))) {
                    out[1] = val.trim();
                }
            }
            if (!out[0].isEmpty() || !out[1].isEmpty()) {
                break;
            }
        }
        return out;
    }

    public static String readStandardGasCylinderConcentration(EcatCore core, String gasLabel) {
        if (core == null) {
            return "";
        }
        LogicDeviceRegistry reg = core.getLogicDeviceRegistry();
        if (reg == null) {
            return "";
        }
        String calibratorAttr = resolveCalibratorCylinderAttrId(core, gasLabel);
        if (calibratorAttr != null) {
            LogicDevice calibrator = (LogicDevice) reg.getDeviceByID(EntryId.Station.CALIBRATOR);
            if (calibrator != null && calibrator.getAttrMap() != null) {
                ILogicAttribute<?> attr = calibrator.getAttrMap().get(calibratorAttr);
                if (attr != null) {
                    String v = attr.getDisplayValue();
                    if (v != null && !v.trim().isEmpty()) {
                        return v.trim();
                    }
                }
            }
        }
        return readStandardGasCylinderFromStandardGasDevices(reg, gasLabel);
    }

    /**
     * 从校准仪逻辑设备的属性定义中解析当前气体对应的钢瓶浓度属性 ID；无对应前缀（如 O₃）返回 {@code null}。
     */
    public static String resolveCalibratorCylinderAttrId(EcatCore core, String gasLabel) {
        String prefix = cylinderKeyPrefixForGasLabel(gasLabel);
        if (prefix == null) {
            return null;
        }
        LogicDeviceRegistry reg = core.getLogicDeviceRegistry();
        if (reg == null) {
            return null;
        }
        LogicDevice cal = (LogicDevice) reg.getDeviceByID(EntryId.Station.CALIBRATOR);
        if (cal == null) {
            return null;
        }
        List<LogicAttributeDefine> defs = cal.getAttrDefs();
        if (defs == null) {
            return null;
        }
        for (LogicAttributeDefine def : defs) {
            String aid = def.getAttrId();
            if (aid == null) {
                continue;
            }
            if (aid.startsWith(prefix + "_") && aid.endsWith("_cylinder_concentration")) {
                return aid;
            }
        }
        return null;
    }

    /**
     * 标准气逻辑设备上「可写的标气浓度」属性：在定义列表中取首个非 mapable、可改、NUMERIC 且原始单位为 PPM 的项（与站点 StandardGas 编排一致）。
     */
    public static String resolveStdGasConcentrationAttrId(LogicDevice cyl) {
        if (cyl == null) {
            return null;
        }
        List<LogicAttributeDefine> defs = cyl.getAttrDefs();
        if (defs == null) {
            return null;
        }
        for (LogicAttributeDefine d : defs) {
            if (d.isMapable() || !d.isValueChangeable()) {
                continue;
            }
            if (d.getAttrClass() != AttributeClass.NUMERIC) {
                continue;
            }
            UnitInfo nu = d.getNativeUnit();
            if (nu instanceof AirVolumeUnit && AirVolumeUnit.PPM.equals(nu)) {
                return d.getAttrId();
            }
        }
        return null;
    }

    /** 逻辑设备定义中是否包含某属性 ID（用于 API 校验）。 */
    public static boolean attributeDefinedOn(LogicDevice ld, String attrId) {
        if (ld == null || attrId == null) {
            return false;
        }
        List<LogicAttributeDefine> defs = ld.getAttrDefs();
        if (defs == null) {
            return false;
        }
        for (LogicAttributeDefine d : defs) {
            if (attrId.equals(d.getAttrId())) {
                return true;
            }
        }
        return false;
    }

    private static String cylinderKeyPrefixForGasLabel(String gasLabel) {
        if (gasLabel == null) {
            return null;
        }
        switch (gasLabel.trim().toUpperCase(Locale.ROOT)) {
            case "SO2":
                return GasKey.SO2;
            case "NO":
            case "NO2":
                return GasKey.NO;
            case "CO":
                return GasKey.CO;
            default:
                return null;
        }
    }

    private static String readStandardGasCylinderFromStandardGasDevices(
            LogicDeviceRegistry reg, String gasLabel) {
        for (String instance : cylinderInstancesForLabel(gasLabel)) {
            LogicDevice cyl = (LogicDevice) reg.getDeviceByID(EntryId.Station.standardGas(instance));
            if (cyl == null || cyl.getAttrMap() == null) {
                continue;
            }
            String concAttr = resolveStdGasConcentrationAttrId(cyl);
            if (concAttr == null) {
                continue;
            }
            ILogicAttribute<?> attr = cyl.getAttrMap().get(concAttr);
            if (attr != null) {
                String v = attr.getDisplayValue();
                if (v != null && !v.trim().isEmpty()) {
                    return v.trim();
                }
            }
        }
        return "";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 从逻辑设备 mappings 中取出首个物理 device_id（报表与任务侧统一用此解析，不再单独维护一份映射表）。
     */
    @SuppressWarnings("unchecked")
    public static String getFirstMappedPhysicalDeviceId(LogicDevice ld) {
        if (ld == null || ld.getEntry() == null || ld.getEntry().getData() == null) {
            return null;
        }
        Map<String, Object> data = ld.getEntry().getData();
        Object mappingsObj = data.get("mappings");
        if (!(mappingsObj instanceof Map)) {
            return null;
        }
        Map<?, ?> mappings = (Map<?, ?>) mappingsObj;
        for (Object v : mappings.values()) {
            if (v instanceof Map) {
                Object id = ((Map<?, ?>) v).get("device_id");
                if (id instanceof String && !((String) id).isEmpty()) {
                    return (String) id;
                }
            }
        }
        return null;
    }

    /**
     * 报表用气态参数名（SO2、NO2、O3、CO）从 {@link EcatCore#getLogicDeviceRegistry()} 取标准分析仪逻辑设备，
     * 再读其 {@code data.mappings} 中的物理 {@code device_id}。
     * <p>解析顺序：① 与主浓度通道 {@link AttributeClass} 一致的属性在 mappings 中的 {@code device_id}（多机/多属性时与报表主读数一致）；
     * ② 任意 mapping 条目中首个非空 {@code device_id}（兼容旧配置）。</p>
     *
     * @return 未注册逻辑设备、无 mappings、或全部为「无物理设备」/ 未填 device_id 时 {@code null}
     */
    public static String resolveAnalyzerPhysicalDeviceId(EcatCore core, String parameterName) {
        if (core == null) {
            return null;
        }
        LogicDevice ld = resolveGasAnalyzer(core, parameterName);
        if (ld == null) {
            logger.debug(
                    "报表解析物理 device_id：气体 {} 的逻辑入口 {} 未在 LogicDeviceRegistry 中注册（或注册表不可用）",
                    parameterName,
                    LogicDeviceBindingIds.analyzerEntryIdForParameterName(parameterName));
            return null;
        }
        String primary = physicalDeviceIdFromPrimaryConcentrationMapping(ld, parameterName);
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }
        String first = getFirstMappedPhysicalDeviceId(ld);
        if (first != null && !first.isEmpty()) {
            return first;
        }
        logger.debug(
                "报表解析物理 device_id：逻辑设备 uniqueId={} 的 data.mappings 中无非空 device_id。"
                        + " 常见原因：各属性选择「无物理设备」、仅占位、或 mappings 结构与预期不符。mappingsKeys={}",
                ld.getId(),
                mappingKeysSummary(ld));
        return null;
    }

    private static String mappingKeysSummary(LogicDevice ld) {
        if (ld == null || ld.getEntry() == null || ld.getEntry().getData() == null) {
            return "(no entry data)";
        }
        Object m = ld.getEntry().getData().get("mappings");
        if (!(m instanceof Map)) {
            return "(mappings missing or not a map)";
        }
        return String.valueOf(((Map<?, ?>) m).keySet());
    }

    /**
     * 从 mappings 中取与当前报表气体主浓度 {@link AttributeClass} 对应的首个属性上的 {@code device_id}。
     */
    @SuppressWarnings("unchecked")
    private static String physicalDeviceIdFromPrimaryConcentrationMapping(LogicDevice ld, String parameterName) {
        if (ld == null || ld.getEntry() == null || ld.getEntry().getData() == null) {
            return null;
        }
        Object mappingsObj = ld.getEntry().getData().get("mappings");
        if (!(mappingsObj instanceof Map)) {
            return null;
        }
        Map<?, ?> mappings = (Map<?, ?>) mappingsObj;
        List<LogicAttributeDefine> defs = ld.getAttrDefs();
        if (defs == null) {
            return null;
        }
        for (LogicAttributeDefine def : defs) {
            if (def == null || def.getAttrId() == null) {
                continue;
            }
            if (!matchesPrimaryGasConcentration(parameterName, def.getAttrClass())) {
                continue;
            }
            Object cfgObj = mappings.get(def.getAttrId());
            if (!(cfgObj instanceof Map)) {
                continue;
            }
            Object id = ((Map<?, ?>) cfgObj).get("device_id");
            if (id instanceof String && !((String) id).isEmpty()) {
                return (String) id;
            }
        }
        return null;
    }

    private static LogicDevice resolveGasAnalyzer(EcatCore core, String parameterName) {
        LogicDeviceRegistry reg = core.getLogicDeviceRegistry();
        if (reg == null) {
            return null;
        }
        String id = LogicDeviceBindingIds.analyzerEntryIdForParameterName(parameterName);
        if (id == null) {
            logger.warn("未知气体参数名，无法解析分析仪逻辑设备: {}", parameterName);
            return null;
        }
        return (LogicDevice) reg.getDeviceByID(id);
    }

    /**
     * 从 mapping 注入的 {@link LogicAttributeDefine} 列表挑选「工况 + 主浓度通道」，顺序与定义列表一致。
     */
    private static List<LabeledAttr> collectAnalyzerKeyParameterSpecs(String parameterName, LogicDevice analyzer) {
        List<LabeledAttr> list = new ArrayList<>();
        List<LogicAttributeDefine> defs = analyzer.getAttrDefs();
        if (defs == null || defs.isEmpty()) {
            logger.debug("分析仪逻辑设备未返回属性定义（mapping 未就绪） deviceId={}", analyzer.getId());
            return list;
        }
        for (LogicAttributeDefine def : defs) {
            if (!isKeyParameterAttrForReport(parameterName, def)) {
                continue;
            }
            String attrId = def.getAttrId();
            list.add(new LabeledAttr(labelForAttrDefine(def), attrId));
        }
        return list;
    }

    /**
     * 是否入选关键参数：工况（流量/压力/温度）+ 与报表气体对应的主浓度 {@link AttributeClass}。
     * NO₂ 报表沿用合并通道 NOx（{@link AttributeClass#NOX}），不含 NO/NO₂ 分项。
     */
    private static boolean isKeyParameterAttrForReport(String parameterName, LogicAttributeDefine def) {
        if (def == null || !def.isDisplayable()) {
            return false;
        }
        AttributeClass ac = def.getAttrClass();
        if (ac == AttributeClass.DISPATCH_COMMAND || ac == AttributeClass.MODE) {
            return false;
        }
        if (ac == AttributeClass.VALUE) {
            return false;
        }
        if (ac == AttributeClass.FLOW || ac == AttributeClass.PRESSURE || ac == AttributeClass.TEMPERATURE) {
            return true;
        }
        return matchesPrimaryGasConcentration(parameterName, ac);
    }

    private static boolean matchesPrimaryGasConcentration(String parameterName, AttributeClass ac) {
        switch (parameterName.trim().toUpperCase(Locale.ROOT)) {
            case "SO2":
                return ac == AttributeClass.SO2;
            case "NO2":
                return ac == AttributeClass.NOX;
            case "O3":
                return ac == AttributeClass.O3;
            case "CO":
                return ac == AttributeClass.CO;
            default:
                return false;
        }
    }

    private static String labelForAttrDefine(LogicAttributeDefine def) {
        String dn = def.getDisplayName();
        if (dn != null && !dn.trim().isEmpty()) {
            return dn.trim();
        }
        return def.getAttrId();
    }

    /**
     * 钢瓶逻辑实例名：与站点 {@code logicdevice_station.standard_gas.*} 配置一致。
     */
    private static String[] cylinderInstancesForLabel(String gasLabel) {
        if (gasLabel == null) {
            return new String[0];
        }
        switch (gasLabel.trim().toUpperCase(Locale.ROOT)) {
            case "SO2":
                return new String[] { GasKey.SO2 };
            case "CO":
                return new String[] { GasKey.CO };
            case "O3":
                return new String[] { GasKey.O3 };
            case "NO":
                return new String[] { GasKey.NO };
            case "NO2":
                return new String[] { "no2", GasKey.NO };
            default:
                return new String[] { GasKey.SO2 };
        }
    }
}
