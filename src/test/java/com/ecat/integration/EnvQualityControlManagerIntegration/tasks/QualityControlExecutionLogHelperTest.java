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

    /**
     * JSON 边界净化：降级运行缺监测仪时读数记 NaN 哨兵，Float.NaN/Infinity 是非法 JSON
     * 数值令牌（序列化成裸 NaN 落库后连本模块的解析链都读不回）——指标遇之一律转 null
     * （语义「没有」），嵌套序列（deviceValues 等）逐元素同规则。
     */
    @Test
    void toExecutionLogJson_nonFiniteMetricsBecomeNull() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("resultValue", Float.NaN);
        metrics.put("stdValue", 100f);
        metrics.put("deviceValue", Float.POSITIVE_INFINITY);
        metrics.put("slope", Double.NaN);
        metrics.put("deviceValues", java.util.Arrays.asList(80f, Float.NaN, 240f));
        TestResult tr = new TestResult(false, false, "监测仪器未配置，监测数据无效", "");

        String json = QualityControlExecutionLogHelper.toExecutionLogJson(
                new LinkedHashMap<>(), metrics, tr, null, null, null);

        assertFalse(json.contains("NaN"), "NaN 不得以任何形态进 JSON");
        assertFalse(json.contains("Infinity"), "Infinity 不得以任何形态进 JSON");
        Map<String, Object> res = (Map<String, Object>) QualityControlExecutionLogHelper.parseRootMap(json).get("result");
        assertNull(res.get("resultValue"));
        assertNull(res.get("deviceValue"));
        assertNull(res.get("slope"));
        assertEquals(100f, ((Number) res.get("stdValue")).floatValue(), 1e-5);
        List<?> deviceValues = (List<?>) res.get("deviceValues");
        assertEquals(3, deviceValues.size());
        assertEquals(80f, ((Number) deviceValues.get(0)).floatValue(), 1e-5);
        assertNull(deviceValues.get(1));
        assertEquals(240f, ((Number) deviceValues.get(2)).floatValue(), 1e-5);
    }

    /** 净化不伤既有路径：有限数值与序列化原样保留（回归锁）。 */
    @Test
    void toExecutionLogJson_finiteMetricsUntouched() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("resultValue", 0.7f);
        metrics.put("deviceValues", java.util.Arrays.asList(80f, 240f));
        TestResult tr = new TestResult(true, false, "ok", "");
        String json = QualityControlExecutionLogHelper.toExecutionLogJson(
                new LinkedHashMap<>(), metrics, tr, null, null, null);
        Map<String, Object> res = (Map<String, Object>) QualityControlExecutionLogHelper.parseRootMap(json).get("result");
        assertEquals(0.7f, ((Number) res.get("resultValue")).floatValue(), 1e-5);
        assertEquals(2, ((List<?>) res.get("deviceValues")).size());
    }

    /** statusMap 暴露缺失设备标记（枚举名串，详情弹窗/备注拼装据此解释降级条件）；空集不落键。 */
    @Test
    void buildStatusMap_missingDevicesExposedAsNames() {
        TestResult degraded = new TestResult(false, false, "监测仪器未配置，监测数据无效", "");
        degraded.setMissingDevices(java.util.Arrays.asList(
                com.ecat.integration.EnvCalibrationComposerIntegration.MissingDevice.TARGET_ANALYZER,
                com.ecat.integration.EnvCalibrationComposerIntegration.MissingDevice.CALIBRATOR));
        Map<String, Object> statusMap = QualityControlExecutionLogHelper.buildStatusMap(degraded);
        assertEquals(java.util.Arrays.asList("TARGET_ANALYZER", "CALIBRATOR"), statusMap.get("missingDevices"));

        Map<String, Object> normal = QualityControlExecutionLogHelper.buildStatusMap(new TestResult(true, false, "ok", ""));
        assertFalse(normal.containsKey("missingDevices"), "全配场景零行为差异：不落 missingDevices 键");
    }

    /** 不合格备注拼降级说明段（人工操作/人工视检措辞，非故障语义）。 */
    @Test
    void remarkSummaryForFailedQC_degradedConditionSegmentsAppended() {
        String log = "{\"result\":{\"resultValue\":null,\"isPass\":false},"
                + "\"statusMap\":{\"isPass\":false,\"resultMessage\":\"监测仪器未配置，监测数据无效\","
                + "\"missingDevices\":[\"TARGET_ANALYZER\"]}}";
        String remark = QualityControlExecutionLogHelper.remarkSummaryForFailedQC(log, "零点核查");
        assertTrue(remark.contains("零点核查"));
        assertTrue(remark.contains("人工视检"), "缺监测仪须拼人工视检说明: " + remark);

        String calibratorLog = "{\"result\":{\"resultValue\":0.5,\"isPass\":false},"
                + "\"statusMap\":{\"isPass\":false,\"resultMessage\":\"跨度核查未通过\",\"missingDevices\":[\"CALIBRATOR\"]}}";
        String calibratorRemark = QualityControlExecutionLogHelper.remarkSummaryForFailedQC(calibratorLog, "跨度核查");
        assertTrue(calibratorRemark.contains("校准仪未配置"), "缺校准仪须拼产气人工操作说明: " + calibratorRemark);
        assertTrue(calibratorRemark.contains("人工操作"));
    }

    /** 合格记录备注维持空串（备注列解释不合格原因；降级条件的合格展示走结论列角标）。 */
    @Test
    void remarkSummaryForFailedQC_passRowStaysEmptyEvenWhenDegraded() {
        String log = "{\"result\":{\"resultValue\":0.5,\"isPass\":true},"
                + "\"statusMap\":{\"isPass\":true,\"missingDevices\":[\"CALIBRATOR\"]}}";
        assertEquals("", QualityControlExecutionLogHelper.remarkSummaryForFailedQC(log, "零点核查"));
    }

    private static final class TestResult extends com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase {
        TestResult(boolean pass, boolean ex, String msg, String err) {
            super(pass, ex);
            setResultMessage(msg);
            setErrorMessage(err);
        }
    }
}
