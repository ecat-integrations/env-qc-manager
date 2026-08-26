package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogicDeviceReportSupportKeyParamTest {

    @Test
    void removePrimaryGasConcentrationRows_dropsAnalyzerConc() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("CO浓度", "1"));
        rows.add(row("SO2浓度", "400"));
        rows.add(row("样气流量", "0.5"));
        rows.add(row("反应室温度", "25"));
        LogicDeviceReportSupport.removePrimaryGasConcentrationRows(rows);
        assertEquals(2, rows.size());
        assertEquals("样气流量", rows.get(0).get("tName"));
        assertEquals("反应室温度", rows.get(1).get("tName"));
    }

    private static Map<String, Object> row(String name, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tName", name);
        m.put("tValue", value);
        return m;
    }
}
