package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
/**
 * @author coffee
 */

class QualityControlExecutionLogHelperTest {

    @Test
    void toExecutionLogJson_includesSnapshotAtRoot() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("parameter", "SO2");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("resultValue", 1.0f);
        metrics.put("isPass", true);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tName", "K");
        row.put("tValue", "1");
        List<Map<String, Object>> snap = Collections.singletonList(row);

        TestResult tr = new TestResult(true, false, "ok", "");
        String json = QualityControlExecutionLogHelper.toExecutionLogJson(params, metrics, tr, snap);

        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
        assertTrue(root.containsKey("params"));
        assertTrue(root.containsKey("result"));
        assertTrue(root.containsKey("statusMap"));
        assertTrue(root.containsKey("keyParametersSnapshot"));
        assertEquals("ok", ((Map<?, ?>) root.get("statusMap")).get("resultMessage"));

        Map<String, Object> m = QualityControlExecutionLogHelper.metricsForReport(json);
        assertEquals(1.0f, ((Number) m.get("resultValue")).floatValue(), 1e-5);
        assertTrue(QualityControlExecutionLogHelper.readIsPass(json));
    }

    @Test
    void toExecutionLogJson_includesStdGasConcentrationAtRoot() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("parameter", "SO2");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("resultValue", 1.0f);
        TestResult tr = new TestResult(true, false, "ok", "");
        String json = QualityControlExecutionLogHelper.toExecutionLogJson(params, metrics, tr, null, "400", null);

        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
        assertEquals("400", root.get(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_KEY));
        assertEquals("400", QualityControlExecutionLogHelper.readStdGasConcentrationSnapshot(json));
        Map<String, Object> m = QualityControlExecutionLogHelper.metricsForReport(json);
        assertFalse(m.containsKey(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_KEY));
    }

    @Test
    void toExecutionLogJson_stdGasConcentrationAndUnitArePaired() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("resultValue", 1.0f);
        TestResult tr = new TestResult(true, false, "ok", "");
        String json = QualityControlExecutionLogHelper.toExecutionLogJson(params, metrics, tr, null, "400", "ppm");

        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
        assertEquals("400", root.get(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_KEY));
        assertEquals("ppm", root.get(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_UNIT_KEY),
                "浓度与单位成对落键（气瓶快照单位修复）");
        Map<String, Object> m = QualityControlExecutionLogHelper.metricsForReport(json);
        assertFalse(m.containsKey(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_UNIT_KEY),
                "单位键不进报表指标 Map");
    }

    /** 单位入参缺席（旧记录语义）：单位键不落（键缺席，解析方按缺单位处理不猜）。 */
    @Test
    void toExecutionLogJson_withoutUnit_omitsUnitKey() {
        Map<String, Object> params = new LinkedHashMap<>();
        TestResult tr = new TestResult(true, false, "ok", "");
        String json = QualityControlExecutionLogHelper.toExecutionLogJson(params, Collections.emptyMap(), tr, null, "400", null);

        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
        assertEquals("400", root.get(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_KEY));
        assertFalse(root.containsKey(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_UNIT_KEY));
    }

    @Test
    void readIsPass_legacyFlat() {
        String legacy = "{\"resultValue\":1,\"isPass\":true}";
        assertTrue(QualityControlExecutionLogHelper.readIsPass(legacy));
    }

    private static final class TestResult extends com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase {
        TestResult(boolean pass, boolean ex, String msg, String err) {
            super(pass, ex);
            setResultMessage(msg);
            setErrorMessage(err);
        }
    }
}
