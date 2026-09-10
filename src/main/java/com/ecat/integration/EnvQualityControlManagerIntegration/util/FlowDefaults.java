package com.ecat.integration.EnvQualityControlManagerIntegration.util;

/**
 * 校准/质控 flow 参数默认值（G-STD-4 魔法值收口）。
 * <p>同一数值曾散落在编排器（跨度浓度）、人工核查任务（默认流量）与 QcCustomServiceImpl 三处，
 * 收口为单一常量源，避免三处漂移。</p>
 *
 * @author coffee
 */
public final class FlowDefaults {

    private FlowDefaults() {
    }

    /** 默认标气流量（L/min）：任务入参未携带 targetFlowLpm 时使用。 */
    public static final double DEFAULT_TARGET_FLOW_LPM = 4.0d;

    /** 跨度核查默认设定浓度（ppb），非 CO 气态参数。 */
    public static final float DEFAULT_SPAN_CONCENTRATION_PPB = 400f;

    /** 跨度核查默认设定浓度（ppb），CO（分析仪侧 mg/m³ 量级）。 */
    public static final float DEFAULT_SPAN_CONCENTRATION_PPB_CO = 40000f;

    /** 默认标气入口名（人工核查任务入参未携带 stdGasInPortName 时使用）。 */
    public static final String DEFAULT_STD_GAS_IN_PORT_NAME = "跨度口";
}
