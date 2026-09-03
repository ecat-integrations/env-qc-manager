package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.core.EcatCore;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.core.State.Unit.UnitInfoFactory;
import com.ecat.integration.EnvAirDeviceManagerIntegration.EnvAirDeviceManagerIntegration;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.AdmParamKind;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.AirDeviceDataSdk;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.SdkParamKey;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.SdkParamMeta;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.SdkSampleRow;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 质控记录「过程展示」解析载荷（详情弹窗 tabs 批 C）：record → 分析仪标识 + 参数目录 + 全参数实时快照
 * + 秒级历史曲线 + 目标浓度辅助线。无状态纯读；数据源全量走 ADM 数据 SDK（方案二 v2），
 * 不再直读 airdevice 逻辑设备属性——过程展示与 ADM 网页历史/实时同数同口径，避免两套读取面口径撕裂。
 *
 * <p><b>解析链（与判定采样同口径，非另起一套猜测规则）</b>：</p>
 * <ol>
 *   <li>{@code record.parameter}（数字码）→ {@link ParameterEnum} 名称（SO2/NO2/O3/CO…）</li>
 *   <li>{@link LogicDeviceBindingIds#composerGasKeyFromParameterName} → composer 采集键——
 *       质控判定的「仪器示值」就是采样线程按该 attrId 逐次读取的，因此曲线主通道
 *       isMain = attrId 精确等于该键（NO2 质控落在 NOx 分析仪的 {@code no} 通道，
 *       通 NO 标气读 NO 通道，与钢瓶槽映射同一用户定案）。</li>
 *   <li>分析仪 indicator = {@code logicdevice.{so2|nox|o3|co}}（qcm 自有绑定词汇
 *       {@link LogicDeviceBindingIds}，与 ADM status_data 深链 indicator 同值）。</li>
 *   <li>SDK 获取按 ADM javadoc 既定模式：registry 取集成实例后 {@code getAirDeviceDataSdk()}；
 *       ADM 未注册/未就绪走 analyzerReason 显式提示（严格模式，不静默回退旧直读链）。</li>
 *   <li>参数目录：{@link AirDeviceDataSdk#listStatParams()} 按 indicator 过滤——kind/displayName
 *       由 ADM 域权威派生（消费方不自判参数分类）。</li>
 * </ol>
 *
 * <p><b>单位口径（D19 / G-BUG-20，用户两次强调单位正确性第一优先）</b>：气态浓度统一按目标单位取值
 * ——CO→PPM、其余→PPB，与质控判定、报告主读数同口径。SDK 入参用 core 单位枚举构造的
 * full key（{@code AirVolumeUnit.getFullUnitString()} 形态，非硬编码字符串）；跨单位类的非气态参数
 * （流量/温度等）换算不可达时 SDK 按契约原值原单位返回，恒一致。响应中的 unit 一律转成前端短显示名。</p>
 *
 * <p><b>秒级历史曲线</b>：{@link AirDeviceDataSdk#queryRawSeries}——stat 聚合最细只到分钟桶，
 * 秒级序列只有 raw 表有（分析仪约 5.7 秒/行）；窗口 = 任务开始−5min（通标气前基线段）到 now，
 * 仅执行中（executionStatus∈{0,1}）生成，已完成记录 series 如实 null（历史回放走冻结数据）。</p>
 */
@Slf4j
public final class QcLiveProcessPayload {

    /** ADM 集成 coordinate（registry 取集成实例的键，与 ADM api javadoc 约定一致）。 */
    private static final String ADM_INTEGRATION_COORDINATE = "com.ecat:integration-env-air-device-manager";

    /** 曲线回看余量：任务开始前 5 分钟（展示通标气前的基线段，与判定采样窗同源的上下文）。 */
    private static final Duration SERIES_PRELUDE = Duration.ofMinutes(5);

    /**
     * SDK queryRawSeries 单窗上限（契约 &gt;24h 抛 IAE）：异常久未结束的执行截断到最近 24h，
     * 查询窗如实体现在 seriesWindow（截断可见，非静默吞）。
     */
    private static final Duration SERIES_MAX_WINDOW = Duration.ofHours(24);

    private QcLiveProcessPayload() {
    }

    /** 兼容旧签名（无计划浓度回落，单测直用）。 */
    public static Map<String, Object> build(EcatCore core, QcmRecord record) {
        return build(core, record, null);
    }

    /**
     * @param planSpanPpb 计划表标气浓度（ppb）——执行中 execution_log 未落 params 时的
     *                    目标浓度线最后一级回落（controller 查 plan 后传入，本类保持无 mapper 纯读）
     */
    public static Map<String, Object> build(EcatCore core, QcmRecord record, java.math.BigDecimal planSpanPpb) {
        Objects.requireNonNull(record, "record");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordId", record.getId());
        out.put("executionStatus", record.getExecutionStatus());

        String gasCode = record.getParameter();
        String parameterName = gasCode != null ? ParameterEnum.getNameByCode(gasCode) : null;
        if (parameterName == null) {
            // 严格模式：参数不可识别就显式说明，不猜默认分析仪
            return unresolved(out, "质控参数代码无法识别（parameter=" + gasCode + "），不能定位分析仪");
        }

        String composerGasKey;
        try {
            composerGasKey = LogicDeviceBindingIds.composerGasKeyFromParameterName(parameterName);
        } catch (IllegalArgumentException e) {
            // PM10/PM2.5 等参数无气态质控执行器，属合法边界——显式提示而非异常
            return unresolved(out, "质控参数 " + parameterName + " 无气态质控执行通道：" + e.getMessage());
        }
        String entryId = LogicDeviceBindingIds.analyzerEntryIdForParameterName(parameterName);

        // CO→PPM、其余→PPB：full key 是 SDK targetUnitFullKey 契约入参，短名是响应展示词（两者成对存在）
        boolean coParameter = "CO".equalsIgnoreCase(parameterName.trim());
        AirVolumeUnit targetUnit = coParameter ? AirVolumeUnit.PPM : AirVolumeUnit.PPB;
        String curveUnitText = coParameter ? "ppm" : "ppb";
        String targetUnitFullKey = targetUnit.getFullUnitString();

        // name/sn 取完成时冻结列（执行中未冻结=null，前端回退 uniqueId 本地映射）——不做物理设备反查
        out.put("analyzer", analyzerIdentity(record, entryId));
        out.put("analyzerReason", null);
        out.put("curveUnit", curveUnitText);

        AirDeviceDataSdk sdk = null;
        try {
            sdk = resolveSdk(core);
        } catch (IllegalStateException e) {
            // ADM 未注册/未完成初始化：reason 显式提示，其余板块如实置空（严格模式不静默兜底）
            out.put("analyzerReason", "过程展示数据源不可用：" + e.getMessage());
        }
        if (sdk == null) {
            out.put("series", null);
            out.put("seriesWindow", null);
            out.put("attrs", new ArrayList<Map<String, Object>>());
            out.put("attrsReason", "ADM 数据 SDK 不可用，无法读取参数目录与实时快照");
            putMarkLine(out, record, planSpanPpb, targetUnit, curveUnitText);
            return out;
        }

        // 参数目录（该分析仪的全部可查参数）：kind/displayName 的权威来源是 ADM STORAGE 行派生
        List<SdkParamMeta> catalog = new ArrayList<>();
        for (SdkParamMeta meta : sdk.listStatParams()) {
            if (meta != null && entryId.equals(meta.getLogicDeviceUniqueId())) {
                catalog.add(meta);
            }
        }
        if (catalog.isEmpty()) {
            out.put("series", null);
            out.put("seriesWindow", null);
            out.put("attrs", new ArrayList<Map<String, Object>>());
            out.put("attrsReason", "ADM 参数目录中无该分析仪（" + entryId + "）的参数（STORAGE 配置行未 provision）");
            putMarkLine(out, record, planSpanPpb, targetUnit, curveUnitText);
            return out;
        }

        out.put("series", null);
        out.put("seriesWindow", null);
        // 仅执行中（0=等待/1=执行中）生成曲线；已完成记录 series=null（契约冻结，前端走冻结数据回放）
        if (isExecuting(record.getExecutionStatus())) {
            putSeries(out, sdk, catalog, composerGasKey, record, targetUnitFullKey);
        }
        out.put("attrs", snapshotAttrs(sdk, catalog, composerGasKey, targetUnitFullKey));
        out.put("attrsReason", null);
        putMarkLine(out, record, planSpanPpb, targetUnit, curveUnitText);
        return out;
    }

    /** 参数不可解析时的统一出口：全板块 null/空表 + reason（严格模式，不猜默认分析仪）。 */
    private static Map<String, Object> unresolved(Map<String, Object> out, String reason) {
        out.put("analyzer", null);
        out.put("analyzerReason", reason);
        out.put("curveUnit", null);
        out.put("series", null);
        out.put("seriesWindow", null);
        out.put("attrs", new ArrayList<Map<String, Object>>());
        out.put("attrsReason", "分析仪未解析，无参数目录");
        out.put("markLine", null);
        out.put("markLineNote", "分析仪未解析，无目标浓度线");
        return out;
    }

    /**
     * 分析仪标识：uniqueId=logic device 入口 ID（ADM status 深链 indicator 词汇）；
     * name/sn=record 完成时冻结列（执行中未冻结=null，前端回退 uniqueId 本地映射）。
     */
    private static Map<String, Object> analyzerIdentity(QcmRecord record, String entryId) {
        Map<String, Object> analyzer = new LinkedHashMap<>();
        analyzer.put("uniqueId", entryId);
        analyzer.put("name", record.getInstrumentName());
        analyzer.put("sn", record.getInstrumentNo());
        return analyzer;
    }

    /**
     * 取 ADM 数据 SDK（ADM javadoc 既定获取模式：registry 取集成实例 → getAirDeviceDataSdk）。
     *
     * @throws IllegalStateException ADM 未注册或尚未完成初始化（消息可直接进 analyzerReason）
     */
    private static AirDeviceDataSdk resolveSdk(EcatCore core) {
        if (core == null || core.getIntegrationRegistry() == null) {
            throw new IllegalStateException("EcatCore / IntegrationRegistry 不可用");
        }
        EnvAirDeviceManagerIntegration adm = (EnvAirDeviceManagerIntegration) core.getIntegrationRegistry()
                .getIntegration(ADM_INTEGRATION_COORDINATE);
        if (adm == null) {
            throw new IllegalStateException("ADM 集成(" + ADM_INTEGRATION_COORDINATE + ")未加载");
        }
        return adm.getAirDeviceDataSdk();
    }

    /** 执行中判定：0=等待 / 1=执行中（ExecutionStatusEnum）；null 按非执行中处理。 */
    private static boolean isExecuting(Integer executionStatus) {
        return executionStatus != null && (executionStatus == 0 || executionStatus == 1);
    }

    /**
     * 全参数实时快照（实时属性表数据源）：{@link AirDeviceDataSdk#queryLatest} 每参数最新 raw 行
     * 与目录 join——kind/displayName 来自元数据；无行参数（从未上报）给 null 值行，如实缺席不猜。
     */
    private static List<Map<String, Object>> snapshotAttrs(AirDeviceDataSdk sdk, List<SdkParamMeta> catalog,
                                                           String composerGasKey, String targetUnitFullKey) {
        Map<String, SdkSampleRow> latestByAttr = new HashMap<>();
        for (SdkSampleRow row : sdk.queryLatest(paramKeys(catalog), targetUnitFullKey)) {
            latestByAttr.put(row.getAttrId(), row);
        }
        List<Map<String, Object>> rows = new ArrayList<>(catalog.size());
        for (SdkParamMeta meta : catalog) {
            SdkSampleRow latest = latestByAttr.get(meta.getAttrId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", meta.getAttrId());
            row.put("name", displayNameOf(meta));
            row.put("kind", meta.getParamKind() != null ? meta.getParamKind().name() : null);
            row.put("isMain", composerGasKey.equals(meta.getAttrId()));
            // value 与曲线 points.v 同型（number/null，契约冻结）：SDK valueNum 直出；文本参数不进本字段
            row.put("value", latest != null ? latest.getValueNum() : null);
            row.put("unit", latest != null ? shortUnitName(latest.getUnit()) : null);
            // 标记数组原样透传（NORMAL/OVER_UPPER_LIMIT...），前端只展示有无异常，不在本层概括
            row.put("statuses", latest != null ? latest.getStatuses() : null);
            row.put("updateTime", latest != null && latest.getDataTime() != null
                    ? latest.getDataTime().toEpochMilli() : null);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 秒级历史曲线（仅执行中调用）：MONITOR 参数全拉（NOx 仪 no/no2/nox 三条曲线同图对比），
     * 窗口 = 任务开始−5 分钟（通标气前基线段）到 now，超 SDK 24h 上限截断（窗体现在 seriesWindow）。
     */
    private static void putSeries(Map<String, Object> out, AirDeviceDataSdk sdk, List<SdkParamMeta> catalog,
                                  String composerGasKey, QcmRecord record, String targetUnitFullKey) {
        List<SdkParamMeta> monitorParams = new ArrayList<>();
        for (SdkParamMeta meta : catalog) {
            if (meta.getParamKind() == AdmParamKind.MONITOR) {
                monitorParams.add(meta);
            }
        }
        Instant end = Instant.now();
        Instant start;
        if (record.getStartTime() != null) {
            start = record.getStartTime().minus(SERIES_PRELUDE);
        } else {
            // 等待中尚未真正开始的记录无过程曲线可言（startTime 未落），不造假窗口
            return;
        }
        Instant windowFloor = end.minus(SERIES_MAX_WINDOW);
        if (start.isBefore(windowFloor)) {
            start = windowFloor;
        }
        if (!start.isBefore(end)) {
            // startTime 在未来（时钟偏差等）时窗为空：不画，不触发 SDK start≥end 校验异常
            return;
        }
        List<SdkSampleRow> sampleRows = sdk.queryRawSeries(paramKeys(monitorParams), start, end, targetUnitFullKey);
        Map<String, List<SdkSampleRow>> rowsByAttr = new HashMap<>();
        for (SdkSampleRow row : sampleRows) {
            rowsByAttr.computeIfAbsent(row.getAttrId(), k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> series = new ArrayList<>(monitorParams.size());
        for (SdkParamMeta meta : monitorParams) {
            List<SdkSampleRow> paramRows = rowsByAttr.getOrDefault(meta.getAttrId(), Collections.emptyList());
            List<Map<String, Object>> points = new ArrayList<>(paramRows.size());
            for (SdkSampleRow row : paramRows) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("t", row.getDataTime() != null ? row.getDataTime().toEpochMilli() : null);
                point.put("v", row.getValueNum());
                points.add(point);
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("attrId", meta.getAttrId());
            s.put("name", displayNameOf(meta));
            // 序列单位取最新一行的 unit（SDK 行升序，末行即最近采样；同参数行单位一致）
            s.put("unit", paramRows.isEmpty() ? null : shortUnitName(paramRows.get(paramRows.size() - 1).getUnit()));
            s.put("isMain", composerGasKey.equals(meta.getAttrId()));
            s.put("points", points);
            series.add(s);
        }
        out.put("series", series);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start", start.toEpochMilli());
        window.put("end", end.toEpochMilli());
        out.put("seriesWindow", window);
    }

    /** 目录 → SDK 查询键列表（保持目录序，batch 单 SQL 的入参形态）。 */
    private static List<SdkParamKey> paramKeys(List<SdkParamMeta> catalog) {
        List<SdkParamKey> keys = new ArrayList<>(catalog.size());
        for (SdkParamMeta meta : catalog) {
            keys.add(SdkParamKey.builder()
                    .logicDeviceUniqueId(meta.getLogicDeviceUniqueId())
                    .attrId(meta.getAttrId())
                    .build());
        }
        return keys;
    }

    /**
     * SDK unit 全 key（{@code AirVolumeUnit.PPB} 形态）→ 前端短显示名（ppb/ppm/L/min…）：
     * 经 core {@link UnitInfoFactory} 反解成 {@code UnitInfo} 后取 getName()（枚举短名列）。
     * null/空=无量纲 → null；反解不了的 key 不猜单位，原样返回（ADM 行 unit 列写什么展示什么，长而真）。
     */
    private static String shortUnitName(String unitFullKey) {
        if (unitFullKey == null || unitFullKey.trim().isEmpty()) {
            return null;
        }
        try {
            return UnitInfoFactory.getEnum(unitFullKey).getName();
        } catch (RuntimeException e) {
            log.debug("单位全 key 反解失败，按原文展示: {} - {}", unitFullKey, e.getMessage());
            return unitFullKey;
        }
    }

    private static String displayNameOf(SdkParamMeta meta) {
        String dn = meta.getParamDisplayName();
        return dn != null && !dn.trim().isEmpty() ? dn.trim() : meta.getAttrId();
    }

    /**
     * 目标浓度辅助线（曲线 markLine 数据源）：
     * 零点类=0（通零气）；跨度/人工核查=标气浓度（优先完成时冻结的 standardValue，
     * 运行中未冻结时回落 execution_log params.concentrationPpb，均无则不画并说明）；
     * 多点/精密度/准确度/转换率是序列判定无单一目标，不画。值统一换算到曲线单位（CO 为 ppm）。
     */
    private static void putMarkLine(Map<String, Object> out, QcmRecord record, java.math.BigDecimal planSpanPpb,
                                    AirVolumeUnit targetUnit, String targetUnitText) {
        String qcTypeCode = record.getQualityControlType();
        QualityControlTypeEnum qcType = qcTypeCode != null ? QualityControlTypeEnum.fromCode(qcTypeCode) : null;
        if (qcType == null) {
            out.put("markLine", null);
            out.put("markLineNote", "记录缺少质控类型，无法确定目标浓度线");
            return;
        }
        Double ppb = null;
        String label;
        switch (qcType) {
            case ZERO_CHECK:
            case MULTI_ZERO_CHECK:
                ppb = 0.0;
                label = "零点目标";
                break;
            case SPAN_CHECK:
            case AUDIT_SPAN_CHECK:
                ppb = standardValuePpb(record, planSpanPpb);
                label = "标气浓度";
                break;
            default:
                out.put("markLine", null);
                out.put("markLineNote", "「" + qcType.getDisplayName() + "」为多点/序列判定，无单一目标浓度线");
                return;
        }
        if (ppb == null) {
            out.put("markLine", null);
            out.put("markLineNote", "跨度类质控未取到标气浓度（standardValue 与任务参数均无），不画目标线");
            return;
        }
        double valueInCurveUnit = AirVolumeUnit.PPB.equals(targetUnit)
                ? ppb : ppb / 1000.0; // CO 曲线单位 ppm：ppb→ppm
        Map<String, Object> markLine = new LinkedHashMap<>();
        markLine.put("value", valueInCurveUnit);
        markLine.put("unit", targetUnitText);
        markLine.put("label", label);
        out.put("markLine", markLine);
        out.put("markLineNote", null);
    }

    /** 标气浓度（ppb）：完成记录取冻结列 standardValue；运行中回落 execution_log.params.concentrationPpb。 */
    private static Double standardValuePpb(QcmRecord record, java.math.BigDecimal planSpanPpb) {
        if (record.getStandardValue() != null) {
            return record.getStandardValue().doubleValue();
        }
        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(record.getExecutionLog());
        Object params = root.get("params");
        Object conc = params instanceof Map ? ((Map<?, ?>) params).get("concentrationPpb") : null;
        // 禁用「instanceof ? double : Double」三元：混合类型公共类型是 double，parseFloatOrNull 返回 null
        // 会被隐式拆箱直接 NPE（运行中未冻结且无任务参数回落的记录即命中）
        if (conc instanceof Number) {
            return ((Number) conc).doubleValue();
        }
        if (conc != null) {
            Float parsed = parseFloatOrNull(String.valueOf(conc));
            return parsed != null ? parsed.doubleValue() : null;
        }
        // 最后一级回落：计划表标气浓度——执行中 execution_log 完成时才写 params，
        // 计划触发的记录 planId 始终在库，曲线打开即有目标线（controller 查 plan 后传入）
        return planSpanPpb != null ? planSpanPpb.doubleValue() : null;
    }

    private static Float parseFloatOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || "null".equals(t)) {
            return null;
        }
        try {
            return Float.parseFloat(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
