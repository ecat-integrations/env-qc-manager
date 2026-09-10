package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import java.util.Locale;

/**
 * 分析仪「运行状况检查」类报表中关键参数的参考正常范围（多厂商汇总，第一版文案）。
 * <p>用于填充关键参数行的 {@code tRange}（正常范围）列；与 {@link ParameterEnum} 气体及逻辑设备展示名 {@code tName} 做启发式匹配。</p>
 *
 * @author coffee
 */
public final class AnalyzerOperatingStatusNormalRanges {

    private AnalyzerOperatingStatusNormalRanges() {
    }

    /**
     * @param parameterEnumCode {@link ParameterEnum#getCode()}，如 SO2=1、NO2=2、O3=3、CO=4
     * @param parameterDisplayName 关键参数展示名（与 {@code tName} 一致）
     * @return 正常范围说明；无法识别或暂无资料时返回空串
     */
    public static String lookupByParameterCode(String parameterEnumCode, String parameterDisplayName) {
        String gas = ParameterEnum.getNameByCode(parameterEnumCode);
        return lookupByParameterName(gas, parameterDisplayName);
    }

    /**
     * @param parameterName {@link ParameterEnum} 名称：SO2 / NO2 / O3 / CO
     */
    public static String lookupByParameterName(String parameterName, String parameterDisplayName) {
        if (parameterName == null || parameterDisplayName == null) {
            return "";
        }
        String gas = parameterName.trim().toUpperCase(Locale.ROOT);
        String n = parameterDisplayName.trim();
        if (n.isEmpty()) {
            return "";
        }
        switch (gas) {
            case "SO2":
                return matchSo2(n);
            case "NO2":
                return matchNox(n);
            case "O3":
                return matchO3(n);
            case "CO":
                return matchCo(n);
            default:
                return "";
        }
    }

    private static String matchSo2(String n) {
        if (n.contains("增益") || n.contains("背景")) {
            return "";
        }
        if (n.contains("反应室") && (n.contains("温") || n.contains("温度"))) {
            return SO2_REACTION_CHAMBER_TEMP;
        }
        if (n.contains("高压") || n.contains("倍增管")) {
            return SO2_HV_SUPPLY;
        }
        if (n.contains("截距") || n.toLowerCase(Locale.ROOT).contains("offset")) {
            return SO2_OFFSET;
        }
        if (n.contains("斜率") || n.toLowerCase(Locale.ROOT).contains("slope")) {
            return SO2_SLOPE;
        }
        if (n.contains("紫外") || n.contains("光强") || n.contains("锌灯") || n.contains("紫外灯")) {
            return SO2_UV_LAMP_INTENSITY;
        }
        if (n.contains("PMT") || n.contains("模拟电")) {
            return SO2_REF_PMT;
        }
        if (n.contains("流量")) {
            return SO2_SAMPLE_FLOW;
        }
        if (n.contains("压力") || n.contains("样气压力")) {
            return SO2_SAMPLE_PRESSURE;
        }
        return "";
    }

    private static String matchNox(String n) {
        if (n.contains("增益") || n.contains("背景")) {
            return "";
        }
        if (n.contains("转化炉")) {
            return NOX_CONVERTER_TEMP;
        }
        if ((n.contains("反应室") || n.contains("测量室")) && (n.contains("压") || n.contains("真空"))) {
            return NOX_REACTION_CHAMBER_PRESSURE;
        }
        if ((n.contains("反应室") || n.contains("测量室") || n.contains("试样槽"))
                && (n.contains("温") || n.contains("温度"))) {
            return NOX_REACTION_CHAMBER_TEMP;
        }
        if (n.contains("臭氧") && (n.contains("流量") || n.contains("氧量") || n.contains("氧"))) {
            return NOX_OZONE_FLOW;
        }
        if (n.contains("高压") || (n.contains("光电倍增管") && n.contains("高压"))) {
            return NOX_HV_SUPPLY;
        }
        if (n.contains("PMT") || n.contains("模拟电压") || (n.contains("光电倍增管") && n.contains("信号"))) {
            return NOX_REF_PMT;
        }
        String uc = n.toUpperCase(Locale.ROOT);
        if (n.contains("截距") || n.toLowerCase(Locale.ROOT).contains("offset")) {
            if (isNoxChannelName(n, uc)) {
                return NOX_NOX_OFFSET;
            }
            if (n.contains("一氧化氮") || isLikelyNoOnly(n, uc)) {
                return NOX_NO_OFFSET;
            }
            return NOX_NOX_OFFSET;
        }
        if (n.contains("斜率") || n.toLowerCase(Locale.ROOT).contains("slope")) {
            if (isNoxChannelName(n, uc)) {
                return NOX_NOX_SLOPE;
            }
            if (n.contains("一氧化氮") || isLikelyNoOnly(n, uc)) {
                return NOX_NO_SLOPE;
            }
            return NOX_NOX_SLOPE;
        }
        if (n.contains("流量") || n.contains("总量")) {
            return NOX_SAMPLE_FLOW;
        }
        if (n.contains("压力") || n.contains("样气压力") || n.contains("试样压力")) {
            return NOX_SAMPLE_PRESSURE;
        }
        return "";
    }

    /** NOx 汇总通道：氮氧化物 / NOX / NO₂ 等（非单独 NO 通道）。 */
    private static boolean isNoxChannelName(String n, String uc) {
        return n.contains("氮氧化") || uc.contains("NOX") || n.contains("NO₂") || n.contains("NO2");
    }

    /**
     * 启发式：名称里出现 NO 但非 NOX/NO₂ 等，视为 NO 分项（如「NO 斜率」）。
     */
    private static boolean isLikelyNoOnly(String n, String uc) {
        if (uc.contains("NOX") || n.contains("NO₂") || n.contains("NO2") || n.contains("氮氧化")) {
            return false;
        }
        return n.contains("NO") || n.contains("一氧化氮");
    }

    private static String matchO3(String n) {
        if (n.contains("增益") || n.contains("背景")) {
            return "";
        }
        if (n.contains("样品温度") || n.contains("光室") || n.contains("光室内温度")) {
            return O3_SAMPLE_CELL_TEMP;
        }
        if (n.contains("截距") || n.toLowerCase(Locale.ROOT).contains("offset")) {
            return O3_OFFSET;
        }
        if (n.contains("斜率") || n.toLowerCase(Locale.ROOT).contains("slope")) {
            return O3_SLOPE;
        }
        if (n.contains("测量信号") || n.contains("测量电压") || n.contains("紫外测量") || n.contains("检测器信号")) {
            return O3_MEASURE_SIGNAL;
        }
        if (n.contains("参比信号") || n.contains("参比电压") || n.contains("紫外参考") || n.contains("参考检测器")) {
            return O3_REFERENCE_SIGNAL;
        }
        if (n.contains("流量")) {
            return O3_SAMPLE_FLOW;
        }
        if (n.contains("压力") || n.contains("样气压力")) {
            return O3_SAMPLE_PRESSURE;
        }
        return "";
    }

    private static String matchCo(String n) {
        if (n.contains("增益") || n.contains("背景")) {
            return "";
        }
        if (n.contains("相关轮") || n.contains("GFC")) {
            return CO_GFC_WHEEL_TEMP;
        }
        if (n.contains("光学室") || n.contains("光室温度") || (n.contains("光室") && n.contains("温"))) {
            return CO_OPTICAL_BENCH_TEMP;
        }
        if (n.contains("样品温度") || (n.contains("样品") && n.contains("温"))) {
            return CO_SAMPLE_TEMP;
        }
        if (n.contains("测量") && n.contains("参比") && (n.contains("比") || n.contains("/"))) {
            return CO_MEASURE_REF_RATIO;
        }
        if (n.contains("参比信号") || n.contains("参比电压")) {
            return CO_REFERENCE_SIGNAL;
        }
        if (n.contains("测量信号") || n.contains("测量电压")) {
            return CO_MEASURE_SIGNAL;
        }
        if (n.contains("截距") || n.toLowerCase(Locale.ROOT).contains("offset")) {
            return CO_OFFSET;
        }
        if (n.contains("斜率") || n.toLowerCase(Locale.ROOT).contains("slope")) {
            return CO_SLOPE;
        }
        if (n.contains("流量")) {
            return CO_SAMPLE_FLOW;
        }
        if (n.contains("压力") || n.contains("样气压力")) {
            return CO_SAMPLE_PRESSURE;
        }
        return "";
    }

    // --- SO2 ---

    private static final String SO2_SAMPLE_PRESSURE =
            "环境压力±2 In-Hg-A（API），7～15 PSIA（聚光），400～1000 mmHg（赛默飞），50～105 kPa（天虹），"
                    + "13～79 kPa（Dasibi），550～825 TORR（EC），60～110 kPa（新先河），500～1200 hPa（ESA），60～110 kPa（赛默森）";

    private static final String SO2_SAMPLE_FLOW =
            "650±10 ml/min（API），300～800 ml/min（天虹），0.5～0.8 L/min（Dasibi），580～720 sccm（聚光），"
                    + "0.350～0.750 L/min（赛默飞），0.4～0.8 SLPM（EC），400～800 ml/min（新先河），5～90 L/h（ESA），"
                    + "400～800 ml/min（赛默森）";

    private static final String SO2_REF_PMT =
            "0～5000 mV（API），1.5～4.096 V（聚光），11.6～12.2 V（EC），0～4800 mV（新先河），0～200 mV（Dasibi），"
                    + "40～400 mV（ESA），0～4800 mV（赛默森）";

    private static final String SO2_UV_LAMP_INTENSITY =
            "1000～4800 mV（API），1.5～4.096 V（聚光），40～100（赛默飞），300～3000 mV（天虹），3.5～9.0 V（Dasibi），"
                    + "1.5～3.5 V（EC），100～1000 mV（ESA）";

    private static final String SO2_SLOPE =
            "1±0.3（API），0.85～1.15（聚光），0.500～2.000（赛默飞），0.4～2（天虹），0.1～0.3（新先河），2±1（ESA），"
                    + "0.1～0.3（赛默森）";

    private static final String SO2_OFFSET =
            "<250 mV（API），-20～50 ppb（聚光），>0（赛默飞），-50～50 ppb（天虹），0～500 mV（新先河），0±5 ppb（ESA），"
                    + "0～700 mV（赛默森）";

    private static final String SO2_HV_SUPPLY =
            "400～900 V（API），400～900 V（聚光），-1200～-500 V（赛默飞），680～730 V（EC），-500～-1100 V（天虹），"
                    + "600～900 V（Dasibi），400～1000 V（新先河），2400～3200 mV（ESA），400～1000 V（赛默森）";

    private static final String SO2_REACTION_CHAMBER_TEMP =
            "50±1℃（API），50±5℃（聚光/天虹），43～47℃（赛默飞），32℃～45℃（Dasibi），50±5℃（EC），40～50℃（新先河），"
                    + "48～52℃（ESA），45±5℃（赛默森）";

    // --- NOx（报表气体为 NO2）---

    private static final String NOX_SAMPLE_FLOW =
            "500±50 ml/min（API），0.54～0.66 SLPM（EC），350～650 sccm（聚光），0.350～0.900 L/min（赛默飞），"
                    + "300～800 ml/min（天虹），0.15～0.5 L/min（Dasibi），400～800 ml/min（新先河），35～48 L/h（ESA），"
                    + "400～800 ml/min（赛默森）";

    private static final String NOX_OZONE_FLOW =
            "80±15 ml/min（API），72～88 sccm（聚光），>50 mL/min（赛默飞），50～120 ml/min（天虹），60～110 ml/min（新先河），"
                    + "0.02～0.07 L/min（Dasibi），3～10 L/h（ESA），60～110 ml/min（赛默森）";

    private static final String NOX_REF_PMT =
            "0～5000 mV（API），40～400 mV（ESA），1.5～4.096 V（聚光），-200～5000 mV（新先河），-100～4500 mV（赛默森）";

    private static final String NOX_HV_SUPPLY =
            "400～900 V（API），630～680 V（EC），450～900 V（聚光），450～750 mV（ESA），-1200～0 V（赛默飞），"
                    + "-900～-500 V（天虹），500～950 V（Dasibi），400～900 V（赛默森）";

    private static final String NOX_REACTION_CHAMBER_TEMP =
            "50±1℃（API），45.0～55.0℃（EC），50±1℃（聚光），43±0.5℃（Dasibi），48～52℃（赛默飞），45～55℃（天虹），"
                    + "45～55℃（新先河），55～65℃（ESA），50±5℃（赛默森）";

    private static final String NOX_CONVERTER_TEMP =
            "315±5℃（API），310～330℃（EC），305～350℃（聚光），300～350℃（赛默飞），180～325℃（天虹），260～290℃（Dasibi），"
                    + "300～330℃（新先河），335～345℃（ESA），280～320℃（赛默森）";

    private static final String NOX_REACTION_CHAMBER_PRESSURE =
            "＜10 In-Hg-A（API），0～6 psia（聚光），10～35 kPa（新先河），50～240 TORR（EC），140～230 hPa（ESA），"
                    + "10～35 kPa（赛默森）";

    private static final String NOX_SAMPLE_PRESSURE =
            "环境压力±2 In-Hg-A（API），550～825 TORR（EC），0～15 psia（聚光），150～300 mmHg（赛默飞），50～105 kPa（天虹），"
                    + "8～40 mmHg（Dasibi），60～110 kPa（新先河），500～1200 hPa（ESA），60～110 kPa（赛默森）";

    private static final String NOX_NOX_SLOPE =
            "1±0.3（API），0.85～1.15（聚光），0.500～2.000（赛默飞），0.5～2（天虹），0.1～0.3（新先河），1±0.5（ESA），"
                    + "0.1～0.3（赛默森）";

    private static final String NOX_NOX_OFFSET =
            "-50～150 mV（API），-20～50 ppb（聚光），>0（赛默飞），-50～50 ppb（天虹），-100～100 mV（新先河），"
                    + "0±5 ppb（ESA），-100～100 mV（赛默森）";

    private static final String NOX_NO_SLOPE =
            "1±0.3（API），0.85～1.15（聚光），0.500～2.000（赛默飞），0.5～2（天虹），0.1～0.3（新先河），1±0.5（ESA），"
                    + "0.1～0.3（赛默森）";

    private static final String NOX_NO_OFFSET =
            "-50～150 mV（API），-20～50 ppb（聚光），>0（赛默飞），-50～50 ppb（天虹），-70～70 mV（新先河），0±5 ppb（ESA），"
                    + "-100～100 mV（赛默森）";

    // --- O3 ---

    private static final String O3_MEASURE_SIGNAL =
            "2500～4800 mV（API），1.5～4.096 V（聚光），500～3000 mV（ESA），45000～150000 Hz（赛默飞），2300～5200 mV（天虹），"
                    + "2000～4900 mV（新先河），2000～3500 mV（赛默森）";

    private static final String O3_REFERENCE_SIGNAL =
            "2500～4800 mV（API），1.5～4.096 V（聚光），500～3000 mV（ESA），45000～150000 Hz（赛默飞），2300～5200 mV（天虹），"
                    + "2000～4900 mV（新先河），2000～3500 mV（赛默森）";

    private static final String O3_SAMPLE_PRESSURE =
            "环境压力±2 In-Hg-A（API），550～825 TORR（EC），7～15 PSIA（聚光），200～1000 mmHg（赛默飞），50～105 kPa（天虹），"
                    + "90～106 kPa（Dasibi），60～110 kPa（新先河），500～1200 hPa（ESA），60～110 kPa（赛默森）";

    private static final String O3_SAMPLE_FLOW =
            "800±10 ml/min（API），0.4～0.6 LPM（EC），720～880 SCCM（聚光），0.400～1.400 L/min（赛默飞 A/B 路），"
                    + "300～1500 ml/min（天虹），1.2～1.7 L/min（Dasibi），800±80 ml/min（新先河），30～90 L/h（ESA），"
                    + "800±80 ml/min（赛默森）";

    private static final String O3_SAMPLE_CELL_TEMP =
            "10～50℃（API），15～45℃（赛默飞），20～60℃（天虹），20～43℃（Dasibi），0～55℃（新先河），5～45℃（ESA），"
                    + "5～45℃（赛默森）";

    private static final String O3_SLOPE =
            "1.0±0.3（API），0.85～1.15（聚光），0.500～2.000（赛默飞），0.5～2（天虹），0.5～1.5（新先河），1.0±0.5（ESA），"
                    + "0.5～1.5（赛默森）";

    private static final String O3_OFFSET =
            "±5 ppb（API），-20～50 ppb（聚光），>-26.5（赛默飞），-50～50（天虹），-20～20 nmol/mol（新先河），0±5 ppb（ESA），"
                    + "-20～20 nmol/mol（赛默森）";

    // --- CO ---

    private static final String CO_MEASURE_SIGNAL =
            "2500～4800 mV（API），1～4 V（聚光），2000～10000 mV（天虹），300～4900 mV（新先河），50～1200 mV（ESA），"
                    + "2000～4500 mV（赛默森）";

    private static final String CO_REFERENCE_SIGNAL =
            "2500～4800 mV（API），1～4 V（聚光），2000～10000 mV（天虹），5000～9000 mV（Dasibi），300～4900 mV（新先河），"
                    + "50～1200 mV（ESA），2000～4500 mV（赛默森）";

    private static final String CO_MEASURE_REF_RATIO =
            "1.1～1.3（API），1.1～1.3（聚光），20～90 mV（零气信号 M）/120～300 mV（标气信号 M）（Dasibi）";

    private static final String CO_SAMPLE_PRESSURE =
            "环境压力±2 In-Hg-A（API），550～825 TORR（EC），7～15 PSIA（聚光），250～1000 mmHg（赛默飞），50～105 kPa（天虹），"
                    + "79～106 kPa（Dasibi），60～110 kPa（新先河），500～1200 hPa（ESA），60～110 kPa（赛默森）";

    private static final String CO_SAMPLE_FLOW =
            "800±10 ml/min（API），0.8～1.1 LPM（EC），720～880 SCCM（聚光），0.350～1.500 L/min（赛默飞），400～1500 ml/min（天虹），"
                    + "0.8～1.2 L/min（Dasibi），500～1200 ml/min（新先河），5～90 L/h（ESA），700～1200 ml/min（赛默森）";

    private static final String CO_SAMPLE_TEMP =
            "48±4℃（API），15～55℃（聚光），8～47℃（赛默飞），20～45℃（Dasibi），44～48℃（ESA）";

    private static final String CO_OPTICAL_BENCH_TEMP =
            "48±2℃（API），47～49℃（聚光），40～55℃（EC），40～52℃（赛默飞），45～55℃（天虹），40～45℃（Dasibi），"
                    + "35～60℃（新先河），44～48℃（ESA），45±5℃（赛默森）";

    private static final String CO_GFC_WHEEL_TEMP =
            "68±2℃（API），66～70℃（聚光），44±1℃（Dasibi），40～70℃（新先河），40～50℃（ESA），50±10℃（赛默森）";

    private static final String CO_SLOPE =
            "1±0.3（API），0.85～1.15（聚光），0.500～2.000（赛默飞），0.5～2（天虹），0.5～2（新先河），1±0.5（ESA），"
                    + "1±0.5（赛默森）";

    private static final String CO_OFFSET =
            "0±0.3 ppm（API），-200～500（聚光），＞-10.75（赛默飞），-5～5 ppm（天虹），-6.0～6.0 μmol/mol（新先河），"
                    + "0±0.5 ppm（ESA），0±1.0 ppm（赛默森）";
}
