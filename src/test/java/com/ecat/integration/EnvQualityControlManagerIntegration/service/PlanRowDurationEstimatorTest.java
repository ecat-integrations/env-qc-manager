package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EnvCalibrationComposerIntegration.EnvCalibrationComposerIntegration;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorType;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行时长预估缝（集合快捷方式间隔预警的 mock 契约锚点）：与 FR-01-32 estimate /
 * 执行共用同一条 buildFlowParams 组装路径——本测试锁该契约的入参形状（气种映射、
 * 浓度透传、时长覆盖透传、multi 行 instruments 列表）与阶段秒数求和。
 * multi_zero_check 行的真实预估秒数由 e2e 覆盖，单测只锚契约不复现链路。
 *
 * @author coffee
 */
class PlanRowDurationEstimatorTest {

    private EnvCalibrationComposerIntegration composer;
    private PlanRowDurationEstimator estimator;

    @BeforeEach
    void setUp() {
        EcatCore core = mock(EcatCore.class);
        IntegrationRegistry registry = mock(IntegrationRegistry.class);
        composer = mock(EnvCalibrationComposerIntegration.class);
        when(core.getIntegrationRegistry()).thenReturn(registry);
        when(registry.getIntegration("integration-env-calibration-composer")).thenReturn(composer);
        estimator = new PlanRowDurationEstimator(core);
    }

    @Test
    void spanRowSumsPhaseSecondsWithConcentrationFlowParam() {
        PlanSaveDto row = new PlanSaveDto();
        row.setQcType("span_check");
        row.setInstruments(Collections.singletonList("CO"));
        row.setConcentrationPpb(BigDecimal.valueOf(40000));
        when(composer.estimatePhases(
                eq(ExecutorType.getEnum(QualityControlTypeEnum.SPAN_CHECK.getClassName())), eq("co"), any()))
                .thenReturn(Arrays.asList(
                        new PhaseInfo("prepare", "安全初始化", 300),
                        new PhaseInfo("recovery", "恢复", 180)));

        assertEquals(480L, estimator.estimateTotalSeconds(row));

        ArgumentCaptor<Map<String, Object>> flowParams = flowParamsCaptor();
        verify(composer).estimatePhases(
                eq(ExecutorType.getEnum(QualityControlTypeEnum.SPAN_CHECK.getClassName())), eq("co"),
                flowParams.capture());
        assertEquals(40000f, flowParams.getValue().get("spanConcentrationPpb"));
    }

    @Test
    void zeroRowMapsNo2ToComposerNoGasWithZeroConcentration() {
        // NO₂ 质控走 NOx 通道（代码键 NO2 ↔ composer 键 no），零点浓度恒 0
        PlanSaveDto row = new PlanSaveDto();
        row.setQcType("zero_check");
        row.setInstruments(Collections.singletonList("NO2"));
        when(composer.estimatePhases(
                eq(ExecutorType.getEnum(QualityControlTypeEnum.ZERO_CHECK.getClassName())), eq("no"), any()))
                .thenReturn(Collections.singletonList(new PhaseInfo("prepare", "安全初始化", 30)));

        assertEquals(30L, estimator.estimateTotalSeconds(row));

        ArgumentCaptor<Map<String, Object>> flowParams = flowParamsCaptor();
        verify(composer).estimatePhases(
                eq(ExecutorType.getEnum(QualityControlTypeEnum.ZERO_CHECK.getClassName())), eq("no"),
                flowParams.capture());
        assertEquals(0f, flowParams.getValue().get("spanConcentrationPpb"));
    }

    @Test
    void durationOverridesFlowThroughToComposer() {
        PlanSaveDto row = new PlanSaveDto();
        row.setQcType("zero_check");
        row.setInstruments(Collections.singletonList("SO2"));
        row.setDurationOverrides(Collections.singletonMap("stableTimeSeconds", 300));
        when(composer.estimatePhases(any(), any(), any()))
                .thenReturn(Collections.singletonList(new PhaseInfo("prepare", "安全初始化", 30)));

        estimator.estimateTotalSeconds(row);

        ArgumentCaptor<Map<String, Object>> flowParams = flowParamsCaptor();
        verify(composer).estimatePhases(
                eq(ExecutorType.getEnum(QualityControlTypeEnum.ZERO_CHECK.getClassName())), eq("so2"),
                flowParams.capture());
        assertEquals(300, flowParams.getValue().get("stableTimeSeconds"));
    }

    @Test
    void multiRowEstimateSharesExecutionFlowParamsAssembly() {
        // multi 行预估与执行共用同一条 buildFlowParams：解闸加分支后此处自动转正，
        // 锚定 instruments=gas key 列表 / 浓度 0 / 流量缺省 5 / 策略透传的契约形状
        PlanSaveDto row = new PlanSaveDto();
        row.setQcType("multi_zero_check");
        row.setInstruments(Arrays.asList("SO2", "NO2", "CO", "O3"));
        row.setCalibrationPolicy("CALIBRATE_LOW_DRIFT");
        when(composer.estimatePhases(
                eq(ExecutorType.getEnum(QualityControlTypeEnum.MULTI_ZERO_CHECK.getClassName())),
                eq("so2"), any()))
                .thenReturn(Arrays.asList(
                        new PhaseInfo("prepare", "安全初始化", 30),
                        new PhaseInfo("zero", "多仪零点采集", 900),
                        new PhaseInfo("recovery", "设备恢复", 120)));

        assertEquals(1050L, estimator.estimateTotalSeconds(row));

        ArgumentCaptor<Map<String, Object>> flowParams = flowParamsCaptor();
        verify(composer).estimatePhases(
                eq(ExecutorType.getEnum(QualityControlTypeEnum.MULTI_ZERO_CHECK.getClassName())),
                eq("so2"), flowParams.capture());
        assertEquals(Arrays.asList("so2", "no", "co", "o3"), flowParams.getValue().get("instruments"));
        assertEquals(0f, flowParams.getValue().get("spanConcentrationPpb"));
        assertEquals(5f, flowParams.getValue().get("flowRateLpm"));
        assertEquals("CALIBRATE_LOW_DRIFT", flowParams.getValue().get("calibrationPolicy"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> flowParamsCaptor() {
        return ArgumentCaptor.forClass((Class) Map.class);
    }
}
