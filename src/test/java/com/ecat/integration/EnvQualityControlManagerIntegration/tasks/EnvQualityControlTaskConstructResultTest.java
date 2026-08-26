package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.EcatCore;
import com.ecat.integration.EnvCalibrationComposerIntegration.AccuracyResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.CheckResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.ConversionResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 校验 {@link EnvQualityControlTask#constructResult} 各质控类型分支互不串数据。
 */
class EnvQualityControlTaskConstructResultTest {

    private static class ExposedTask extends EnvQualityControlTask {
        ExposedTask() {
            super();
        }

        String build(EcatCore core, Map<String, Object> params,
                     com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase result, String qcCode) {
            return constructResult(core, params, result, qcCode, -1L);
        }
    }

    @Test
    void accuracyBranch_doesNotContainEfficiency() {
        ExposedTask t = new ExposedTask();
        AccuracyResult ar = new AccuracyResult();
        ar.setSlope(1f);
        ar.setIntercept(0f);
        ar.setCorrelation(1f);
        ar.setRelativeError(0.1f);
        ar.setPass(true);
        Map<String, Object> p = new HashMap<>();
        p.put("parameter", "SO2");
        String json = t.build(null, p, ar, QualityControlTypeEnum.ACCURACY_CHECK.getCode());
        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) root.get("result");
        assertTrue(res.containsKey("slope"));
        assertTrue(res.containsKey("relativeError"));
        assertFalse(res.containsKey("efficiency"));
    }

    @Test
    void conversionBranch_containsEfficiency_notSlope() {
        ExposedTask t = new ExposedTask();
        ConversionResult cr = new ConversionResult(true, 95f, "");
        cr.setPass(true);
        Map<String, Object> p = new HashMap<>();
        p.put("parameter", "2");
        String json = t.build(null, p, cr, QualityControlTypeEnum.CONVERSION_CHECK.getCode());
        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) root.get("result");
        assertTrue(res.containsKey("efficiency"));
        assertFalse(res.containsKey("slope"));
    }

    @Test
    void zeroCheckBranch_persistsVerificationValue() {
        ExposedTask t = new ExposedTask();
        CheckResult cr = new CheckResult();
        cr.setResult(1.5f);
        cr.setStdValue(0f);
        cr.setDeviceValue(-7.5f);
        cr.setCheckPassLimit(1000f);
        cr.setCheckCalibLimit(2500f);
        cr.setVerificationValue(12.5f);
        cr.setPass(true);
        Map<String, Object> p = new HashMap<>();
        p.put("parameter", "SO2");
        String json = t.build(null, p, cr, QualityControlTypeEnum.ZERO_CHECK.getCode());
        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) root.get("result");
        assertEquals(12.5f, ((Number) res.get("verificationValue")).floatValue(), 1e-5);
        assertFalse(root.containsKey(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_KEY));
    }

    @Test
    void spanCheckBranch_omitsVerificationValueWhenNull() {
        ExposedTask t = new ExposedTask();
        CheckResult cr = new CheckResult();
        cr.setResult(1.1f);
        cr.setStdValue(400f);
        cr.setDeviceValue(395f);
        cr.setCheckPassLimit(5f);
        cr.setCheckCalibLimit(10f);
        cr.setPass(true);
        Map<String, Object> p = new HashMap<>();
        p.put("parameter", "SO2");
        String json = t.build(null, p, cr, QualityControlTypeEnum.SPAN_CHECK.getCode());
        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) root.get("result");
        assertFalse(res.containsKey("verificationValue"));
        assertEquals(400f, ((Number) res.get("stdValue")).floatValue(), 1e-5);
    }
}
