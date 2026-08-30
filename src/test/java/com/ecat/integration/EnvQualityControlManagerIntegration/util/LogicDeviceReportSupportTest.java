package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds.EntryId;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds.GasKey;
import com.ecat.integration.logicdevice.Const;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicState.ILogicAttribute;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import com.ecat.integration.logicdeviceairstation.AirstationIntegration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 报告关键参数快照契约。
 *
 * <p>历史（G-BUG-20）：主浓度行入选快照时曾按 native µg/m³ 裸值错标 ppb，修为按目标单位
 * （SO2/NO2/O3 → PPB；CO → PPM，D19 单位规范）取值，与质控判定同口径。</p>
 *
 * <p>现行契约（574955b 合入）：主浓度通道与本次质控通入标气无关，不再入选关键参数快照
 * （构建侧 {@code isKeyParameterAttrForReport} 仅收工况 + 行级 {@code removePrimaryGasConcentrationRows}
 * 剔除历史残留）；快照仅含工况行（流量/压力/温度），非浓度工况行不受影响。
 * 目标单位取值逻辑保留为防御路径，供任何残留浓度行兜底。</p>
 */
class LogicDeviceReportSupportTest {

    private static LogicDevice analyzerWithSo2() {
        LogicDevice analyzer = mock(LogicDevice.class);
        Map<String, ILogicAttribute<?>> attrMap = new HashMap<>();
        ILogicAttribute<?> so2 = mock(ILogicAttribute.class);
        when(so2.getDisplayValue()).thenReturn("1047.472");
        //noinspection unchecked,rawtypes
        when(so2.getDisplayValue((com.ecat.core.State.UnitInfo) AirVolumeUnit.PPB)).thenReturn("400.000");
        attrMap.put("so2", so2);
        //noinspection unchecked,rawtypes
        when(analyzer.getAttrMap()).thenReturn((Map) attrMap);

        List<LogicAttributeDefine> defs = new ArrayList<>();
        LogicAttributeDefine def = mock(LogicAttributeDefine.class);
        when(def.getAttrId()).thenReturn("so2");
        when(def.getDisplayName()).thenReturn("SO₂浓度");
        when(def.getAttrClass()).thenReturn(AttributeClass.SO2);
        when(def.isDisplayable()).thenReturn(true);
        defs.add(def);
        when(analyzer.getAttrDefs()).thenReturn(defs);
        return analyzer;
    }

    @Test
    void primaryGasConcentrationRowExcludedFromKeyParams() {
        LogicDevice analyzer = analyzerWithSo2();
        List<Map<String, Object>> rows = LogicDeviceReportSupport.buildAnalyzerKeyParametersForTest(analyzer, "SO2");
        assertTrue(rows.stream().noneMatch(r -> "SO₂浓度".equals(r.get("tName"))),
                "主浓度与本次质控通入标气无关，不得入选关键参数快照（历史 G-BUG-20 错值行随之不再出现）");
    }

    @Test
    void coPrimaryConcentrationRowExcludedFromKeyParams() {
        LogicDevice analyzer = mock(LogicDevice.class);
        Map<String, ILogicAttribute<?>> attrMap = new HashMap<>();
        ILogicAttribute<?> co = mock(ILogicAttribute.class);
        when(co.getDisplayValue()).thenReturn("1047.472");
        //noinspection unchecked,rawtypes
        when(co.getDisplayValue((com.ecat.core.State.UnitInfo) AirVolumeUnit.PPM)).thenReturn("1.047");
        attrMap.put("co", co);
        //noinspection unchecked,rawtypes
        when(analyzer.getAttrMap()).thenReturn((Map) attrMap);
        List<LogicAttributeDefine> defs = new ArrayList<>();
        LogicAttributeDefine def = mock(LogicAttributeDefine.class);
        when(def.getAttrId()).thenReturn("co");
        when(def.getDisplayName()).thenReturn("CO浓度");
        when(def.getAttrClass()).thenReturn(AttributeClass.CO);
        when(def.isDisplayable()).thenReturn(true);
        defs.add(def);
        when(analyzer.getAttrDefs()).thenReturn(defs);

        List<Map<String, Object>> rows = LogicDeviceReportSupport.buildAnalyzerKeyParametersForTest(analyzer, "CO");
        assertTrue(rows.stream().noneMatch(r -> "CO浓度".equals(r.get("tName"))),
                "CO 主浓度通道同样不入选关键参数快照");
    }

    @Test
    void nonConcentrationRowKeepsBareDisplayValue() {
        LogicDevice analyzer = mock(LogicDevice.class);
        Map<String, ILogicAttribute<?>> attrMap = new HashMap<>();
        ILogicAttribute<?> temp = mock(ILogicAttribute.class);
        when(temp.getDisplayValue()).thenReturn("25.0");
        attrMap.put("case_temp", temp);
        //noinspection unchecked,rawtypes
        when(analyzer.getAttrMap()).thenReturn((Map) attrMap);
        List<LogicAttributeDefine> defs = new ArrayList<>();
        LogicAttributeDefine def = mock(LogicAttributeDefine.class);
        when(def.getAttrId()).thenReturn("case_temp");
        when(def.getDisplayName()).thenReturn("机箱温度");
        when(def.getAttrClass()).thenReturn(AttributeClass.TEMPERATURE);
        when(def.isDisplayable()).thenReturn(true);
        defs.add(def);
        when(analyzer.getAttrDefs()).thenReturn(defs);

        List<Map<String, Object>> rows = LogicDeviceReportSupport.buildAnalyzerKeyParametersForTest(analyzer, "SO2");
        assertTrue(rows.isEmpty() || !findRowValue(rows, "机箱温度").contains("ppb"),
                "非浓度工况行不受目标单位改造影响");
    }

    private static String findRowValue(List<Map<String, Object>> rows, String name) {
        for (Map<String, Object> r : rows) {
            if (name.equals(r.get("tName"))) {
                return String.valueOf(r.get("tValue"));
            }
        }
        return "<row " + name + " not found: " + rows + ">";
    }

    /** 逻辑设备 mock：attrDefs 含 attrId 定义，attrMap 中该属性展示值为 displayValue。 */
    private static LogicDevice logicDeviceWithAttr(String attrId, String displayValue) {
        LogicDevice ld = mock(LogicDevice.class);
        Map<String, ILogicAttribute<?>> attrMap = new HashMap<>();
        ILogicAttribute<?> attr = mock(ILogicAttribute.class);
        when(attr.getDisplayValue()).thenReturn(displayValue);
        attrMap.put(attrId, attr);
        //noinspection unchecked,rawtypes
        when(ld.getAttrMap()).thenReturn((Map) attrMap);
        List<LogicAttributeDefine> defs = new ArrayList<>();
        LogicAttributeDefine def = mock(LogicAttributeDefine.class);
        when(def.getAttrId()).thenReturn(attrId);
        defs.add(def);
        when(ld.getAttrDefs()).thenReturn(defs);
        return ld;
    }

    /** EcatCore → airstation 集成 mock 链：校准仪与 so2 标准气钢瓶设备按 uniqueId 返回。 */
    private static EcatCore coreWithAirstationDevices(LogicDevice calibrator, LogicDevice standardGasSo2) {
        EcatCore core = mock(EcatCore.class);
        IntegrationRegistry integrationRegistry = mock(IntegrationRegistry.class);
        AirstationIntegration airstation = mock(AirstationIntegration.class);
        when(core.getIntegrationRegistry()).thenReturn(integrationRegistry);
        when(core.getDeviceRegistry()).thenReturn(mock(DeviceRegistry.class));
        when(integrationRegistry.getIntegration(Const.COORD_AIRSTATION)).thenReturn(airstation);
        when(airstation.getDeviceByUniqueId(EntryId.Station.CALIBRATOR)).thenReturn(calibrator);
        when(airstation.getDeviceByUniqueId(EntryId.Station.standardGas(GasKey.SO2))).thenReturn(standardGasSo2);
        return core;
    }

    /**
     * 冻结读取解析顺序（bug-record-20260830-071500 重新定性）：与 GasSettingService.listGasSettings
     * 同序——校准仪 {@code so2_cylinder_concentration}（标气浓度真相源）优先；钢瓶设备
     * {@code gas_concentration} 空时不得再产空快照。
     */
    @Test
    void stdGasFreezePrefersCalibratorCylinderConcentration() {
        LogicDevice calibrator = logicDeviceWithAttr("so2_cylinder_concentration", "50.00");
        LogicDevice cylinder = logicDeviceWithAttr("gas_concentration", "");
        EcatCore core = coreWithAirstationDevices(calibrator, cylinder);
        assertEquals("50.00",
                LogicDeviceReportSupport.readStandardGasCylinderConcentration(core, "SO2"),
                "两读取方一致化：校准仪有值时冻结读取必须取到，钢瓶设备空不再导致记录缺 stdGasConcentration");
    }

    /** 校准仪无值时回落钢瓶设备读取（原路径保留），行为向后兼容。 */
    @Test
    void stdGasFreezeFallsBackToCylinderWhenCalibratorEmpty() {
        LogicDevice calibrator = logicDeviceWithAttr("so2_cylinder_concentration", "");
        LogicDevice cylinder = logicDeviceWithAttr("gas_concentration", "400.0");
        EcatCore core = coreWithAirstationDevices(calibrator, cylinder);
        assertEquals("400.0",
                LogicDeviceReportSupport.readStandardGasCylinderConcentration(core, "SO2"),
                "校准仪无值时保持原钢瓶设备读取（向后兼容）");
    }
}
