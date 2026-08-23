package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicState.ILogicAttribute;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
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
 * 报告关键参数快照取值单位口径（G-BUG-20 回归锁）。
 *
 * <p>背景：airdevice 逻辑设备主浓度属性 native 单位为 µg/m³（ADM 国标口径定案），
 * 裸 {@code getDisplayValue()} 返回 µg/m³ 数值（400ppb → 1047.47），
 * 而快照标签无条件补 " ppb" —— 造成报告「SO₂浓度 1047.472 ppb」数值与标签错位。
 * 判定链路 {@code getDisplayValue(PPB)} 一直正确，仅快照错。</p>
 *
 * <p>契约：主浓度行（SO2/NO2/O3 → PPB；CO → PPM，D19 单位规范）取值必须带目标单位，
 * 与质控判定同口径；非浓度工况行（流量/温度等）不受影响。</p>
 */
class LogicDeviceReportSupportTest {

    private static LogicDevice analyzerWithSo2(String nativeDisplay, String ppbDisplay) {
        LogicDevice analyzer = mock(LogicDevice.class);
        Map<String, ILogicAttribute<?>> attrMap = new HashMap<>();
        ILogicAttribute<?> so2 = mock(ILogicAttribute.class);
        when(so2.getDisplayValue()).thenReturn(nativeDisplay);
        //noinspection unchecked,rawtypes
        when(so2.getDisplayValue((com.ecat.core.State.UnitInfo) AirVolumeUnit.PPB)).thenReturn(ppbDisplay);
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
    void primaryGasConcentrationReadsWithPpbTargetUnitNotNativeUgm3() {
        LogicDevice analyzer = analyzerWithSo2("1047.472", "400.000");
        List<Map<String, Object>> rows = LogicDeviceReportSupport.buildAnalyzerKeyParametersForTest(analyzer, "SO2");
        String so2Row = findRowValue(rows, "SO₂浓度");
        assertEquals("400.000 ppb", so2Row,
                "主浓度快照必须按目标单位 ppb 取值（与判定同口径），不得用 native µg/m³ 值错标 ppb");
    }

    @Test
    void coPrimaryConcentrationUsesPpmUnit() {
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
        assertEquals("1.047 ppm", findRowValue(rows, "CO浓度"),
                "CO 主浓度按 D19 单位规范用 ppm 取值");
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
}
