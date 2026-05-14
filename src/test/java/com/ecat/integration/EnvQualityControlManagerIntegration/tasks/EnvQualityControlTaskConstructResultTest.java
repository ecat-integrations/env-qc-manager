package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.EcatCore;
import com.ecat.integration.EnvCalibrationComposerIntegration.AccuracyResult;
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
            super(new HashMap<>());
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
}
