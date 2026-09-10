package com.ecat.integration.EnvQualityControlManagerIntegration.logic;

/**
 * 本集成在编译期唯一需要绑定的逻辑设备入口 ID（{@link EntryId}）与气态通道小写键（{@link GasKey}）。
 * <p>
 * 校准仪钢瓶浓度、标准气浓度等属性 ID 由运行时 {@link com.ecat.integration.logicdevice.LogicDevice.LogicDevice#getAttrDefs()} 解析，不在此重复定义。
 * 从参数名解析逻辑入口 ID 的映射集中在此；通过 {@link com.ecat.core.EcatCore} 取 {@link com.ecat.integration.logicdevice.LogicDevice.LogicDevice}
 * 实例见 {@link com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport}。
 *
 * @author coffee
 */
public final class LogicDeviceBindingIds {

    private LogicDeviceBindingIds() {
    }

    /** {@code logicdevice_station.*} / {@code logicdevice.*} 入口 ID。 */
    public static final class EntryId {

        private EntryId() {
        }

        public static final class Station {

            private Station() {
            }

            public static final String CALIBRATOR = "logicdevice_station.calibrator";

            public static String standardGas(String instance) {
                return "logicdevice_station.standard_gas." + instance;
            }
        }

        public static final class AirDevice {

            private AirDevice() {
            }

            public static final String SO2 = "logicdevice.so2";
            public static final String NOX = "logicdevice.nox";
            public static final String O3 = "logicdevice.o3";
            public static final String CO = "logicdevice.co";
        }
    }

    /**
     * 气态通道小写键（Composer、{@code standard_gas.*} 实例名等与运行时一致）。
     */
    public static final class GasKey {

        private GasKey() {
        }

        public static final String SO2 = "so2";
        public static final String CO = "co";
        public static final String NO = "no";
        /** 标准气钢瓶多实例后缀，与 {@code logicdevice_station.standard_gas.nox} 一致。 */
        public static final String NOX = "nox";
        public static final String O3 = "o3";
    }

    /**
     * 质控参数名（与 {@code ParameterEnum} 一致）→ env-calibration-composer 使用的气体键。
     * NO₂ 质控走 NOx 通道，对应 Composer 键 {@link GasKey#NO}。
     */
    public static String composerGasKeyFromParameterName(String parameterName) {
        if (parameterName == null || parameterName.isEmpty()) {
            throw new IllegalArgumentException("parameterName is null or empty");
        }
        switch (parameterName.trim().toUpperCase()) {
            case "SO2":
                return GasKey.SO2;
            case "NO2":
                return GasKey.NO;
            case "O3":
                return GasKey.O3;
            case "CO":
                return GasKey.CO;
            default:
                throw new IllegalArgumentException("Unsupported gas parameter: " + parameterName);
        }
    }

    /**
     * 报表 / 任务侧使用的气态参数名（与 {@link com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum} 展示名一致）
     * → 气态分析仪逻辑设备入口 {@link EntryId.AirDevice}；未知或空返回 {@code null}。
     * <p>运行时取设备见 {@link com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport}。</p>
     */
    public static String analyzerEntryIdForParameterName(String parameterName) {
        if (parameterName == null || parameterName.isEmpty()) {
            return null;
        }
        switch (parameterName.trim().toUpperCase()) {
            case "SO2":
                return EntryId.AirDevice.SO2;
            case "NO2":
                return EntryId.AirDevice.NOX;
            case "O3":
                return EntryId.AirDevice.O3;
            case "CO":
                return EntryId.AirDevice.CO;
            default:
                return null;
        }
    }
}
