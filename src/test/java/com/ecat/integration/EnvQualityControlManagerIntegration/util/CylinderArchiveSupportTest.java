package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.core.EcatCore;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 钢瓶档案读写用例（方案 A 2026-08-23）：槽映射闭集（NO2→nox 用户定案 / O3 无槽）、
 * AttrState 单次读取、转写走 setDisplayValue 同通道。
 */
class CylinderArchiveSupportTest {

    @Test
    void slotMapping_codeVocabulary() {
        assertEquals("so2", CylinderArchiveSupport.standardGasSlotFor("1"));
        assertEquals("nox", CylinderArchiveSupport.standardGasSlotFor("2")); // NO2 质控用 NO 标气（用户定案）
        assertEquals("co", CylinderArchiveSupport.standardGasSlotFor("4"));
        assertNull(CylinderArchiveSupport.standardGasSlotFor("3")); // O3 发生器供气
        assertNull(CylinderArchiveSupport.standardGasSlotFor(null));
    }

    /** 档案三要素读取：文本/数值/单位各走一次 AttrState（asm 同款无撕裂读）。 */
    @Test
    @SuppressWarnings("unchecked")
    void readArchive_readsAttrStates() {
        EcatCore core = mock(EcatCore.class);
        LogicDevice device = mock(LogicDevice.class);
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        attrs.put("gas_source", attrWithState("国家标准物质中心"));
        attrs.put("cylinder_id", attrWithState("GBW-E-060522"));
        AttributeBase<?> conc = mock(AttributeBase.class);
        AttrState<?> concState = mock(AttrState.class);
        org.mockito.Mockito.doReturn(Double.valueOf(50.0)).when(concState).getValue();
        UnitInfo ppm = mock(UnitInfo.class);
        when(ppm.getName()).thenReturn("ppm");
        org.mockito.Mockito.doReturn(ppm).when(concState).getDisplayUnit();
        org.mockito.Mockito.doReturn(concState).when(conc).getState();
        attrs.put("gas_concentration", conc);
        when(device.getAttrs()).thenReturn(attrs);

        try (MockedStatic<LogicDeviceReportSupport> support = mockStatic(LogicDeviceReportSupport.class)) {
            support.when(() -> LogicDeviceReportSupport.airstationDevice(
                            eq(core), eq("logicdevice_station.standard_gas.so2")))
                    .thenReturn(device);
            CylinderArchiveSupport.GasTrace trace = CylinderArchiveSupport.readArchive(core, "1");
            assertEquals("国家标准物质中心", trace.gasSource);
            assertEquals("GBW-E-060522", trace.cylinderId);
            assertEquals(0, new BigDecimal("50").compareTo(trace.concentration));
            assertEquals("ppm", trace.concentrationUnit);
        }
    }

    /** 未登记属性（state null/值 null）：字段如实 null（「未设置」默认值由属性层显式可辨）。 */
    @Test
    void readArchive_unregisteredFieldsStayNull() {
        EcatCore core = mock(EcatCore.class);
        LogicDevice device = mock(LogicDevice.class);
        when(device.getAttrs()).thenReturn(new HashMap<>());
        try (MockedStatic<LogicDeviceReportSupport> support = mockStatic(LogicDeviceReportSupport.class)) {
            support.when(() -> LogicDeviceReportSupport.airstationDevice(any(), any())).thenReturn(device);
            CylinderArchiveSupport.GasTrace trace = CylinderArchiveSupport.readArchive(core, "1");
            assertNull(trace.gasSource);
            assertNull(trace.cylinderId);
            assertNull(trace.concentration);
        }
    }

    private static AttributeBase<?> attrWithState(Object value) {
        AttributeBase<?> attr = mock(AttributeBase.class);
        // AttrState<?> 通配泛型经 raw mock + doReturn 绕开 capture 类型不匹配
        AttrState<?> state = mock(AttrState.class);
        org.mockito.Mockito.doReturn(value).when(state).getValue();
        org.mockito.Mockito.doReturn(state).when(attr).getState();
        return attr;
    }
}
