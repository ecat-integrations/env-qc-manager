package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.EcatCore;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvCalibrationComposerIntegration.AccuracyResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.CheckResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.ConversionResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.MultiResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 校验 {@link EnvQualityControlTask#constructResult} 各质控类型分支互不串数据。
 *
 * @author coffee
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

        String buildWithRecordId(EcatCore core, Map<String, Object> params,
                                 com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase result,
                                 String qcCode, long recordId) {
            return constructResult(core, params, result, qcCode, recordId);
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

    /**
     * 回归锁（bugs/bug-record-20260902-161500）：多点检查的 composer 实际载荷是 MultiResult
     * （MultiPointCheckFlow L109），历史上重复条件的 CheckResult 分支抢先命中会 ClassCastException。
     */
    @Test
    void multiCheckBranch_acceptsMultiResult_noCastCrash() {
        ExposedTask t = new ExposedTask();
        MultiResult mr = new MultiResult(1.002f, 1.3f, 0.9996f);
        mr.setDeviceValues(java.util.Arrays.asList(80f, 160f, 240f));
        mr.setStdValues(java.util.Arrays.asList(80f, 160f, 240f));
        mr.setCheck_r_min(0.999f);
        mr.setCheck_a_min(0.95f);
        mr.setCheck_a_max(1.05f);
        mr.setCheck_b_scope(5f);
        mr.setPass(true);
        Map<String, Object> p = new HashMap<>();
        p.put("parameter", "SO2");
        String json = t.build(null, p, mr, QualityControlTypeEnum.MULTI_CHECK.getCode());
        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) root.get("result");
        assertEquals(1.002f, ((Number) res.get("slope")).floatValue(), 1e-5);
        assertEquals(0.9996f, ((Number) res.get("correlation")).floatValue(), 1e-5);
        assertTrue(res.containsKey("check_r_min"));
        assertTrue(res.containsKey("deviceValues"));
        assertFalse(res.containsKey("checkPassLimit"), "多点检查不应再落单值限值字段");
    }

    /**
     * 气瓶快照单位修复：constructResult 完成路径走 {@code CylinderArchiveSupport.readArchive}（钢瓶档案链）
     * 成对取浓度+单位，execution_log 两键都落——旧链裸串无单位，消费方无法判定浓度口径。
     */
    @Test
    void stdGasSnapshot_concentrationAndUnitAreFrozenAsPair() {
        ExposedTask t = new ExposedTask();
        EcatCore core = mock(EcatCore.class);
        LogicDevice cyl = mock(LogicDevice.class);
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        AttributeBase<?> conc = mock(AttributeBase.class);
        AttrState<?> concState = mock(AttrState.class);
        doReturn(Double.valueOf(50.0)).when(concState).getValue();
        UnitInfo ppm = mock(UnitInfo.class);
        when(ppm.getName()).thenReturn("ppm");
        doReturn(ppm).when(concState).getDisplayUnit();
        doReturn(concState).when(conc).getState();
        attrs.put("gas_concentration", conc);
        when(cyl.getAttrs()).thenReturn(attrs);

        try (MockedStatic<LogicDeviceReportSupport> support = mockStatic(LogicDeviceReportSupport.class)) {
            // 钢瓶档案链唯一外部依赖：airstation standard_gas 槽设备（关键参数快照走 mockStatic 默认空）
            support.when(() -> LogicDeviceReportSupport.airstationDevice(
                            eq(core), eq("logicdevice_station.standard_gas.so2")))
                    .thenReturn(cyl);

            CheckResult cr = new CheckResult();
            cr.setResult(1.5f);
            cr.setStdValue(400f);
            cr.setDeviceValue(395f);
            cr.setCheckPassLimit(5f);
            cr.setCheckCalibLimit(10f);
            cr.setPass(true);
            Map<String, Object> p = new HashMap<>();
            p.put("parameter", "SO2");
            String json = t.buildWithRecordId(core, p, cr, QualityControlTypeEnum.ZERO_CHECK.getCode(), 7L);

            Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(json);
            // numericStateOf 走 BigDecimal.valueOf(double)，50.0 的规范串即 "50.0"
            assertEquals("50.0", root.get(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_KEY));
            assertEquals("ppm", root.get(QualityControlExecutionLogHelper.STD_GAS_CONCENTRATION_UNIT_KEY));
        }
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
