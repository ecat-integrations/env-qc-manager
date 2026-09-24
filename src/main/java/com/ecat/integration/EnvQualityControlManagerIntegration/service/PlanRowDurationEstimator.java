package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.EcatCore;
import com.ecat.integration.EnvCalibrationComposerIntegration.EnvCalibrationComposerIntegration;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorType;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 计划行时长预估缝（集合快捷方式间隔预警专用）：输入 plan 行、输出时长预估秒数——
 * 集合工厂只依赖这一契约，预警单测可整体 mock 本缝。
 *
 * <p>内部与 FR-01-32 预估 / 实际执行共用同一条 {@link QcmExecutionOrchestrator#buildFlowParams}
 * 组装路径（预估=实际，禁止双源）。不复用 IQcmPlanService.estimate 的原因：其限定
 * 单仪器，且 multi_zero_check 在 composer 接线前会在 ExecutorType.getEnum 处抛出——
 * 该异常在本缝的原样传播是调用方「预估不可用」语义的一部分，不吞不兜。</p>
 *
 * @author coffee
 */
@Service
public class PlanRowDurationEstimator {

    private static final String COMPOSER_INTEGRATION_ID = "integration-env-calibration-composer";

    private final EcatCore core;

    @Autowired
    public PlanRowDurationEstimator(EcatCore core) {
        this.core = core;
    }

    /**
     * 行时长预估（阶段秒数求和）。
     *
     * @throws RuntimeException 预估链路不可用（如 multi 类型 executor 未接线）——由调用方
     *                           按提示性「预估不可用」处置，不构成建计划的前置失败
     */
    public long estimateTotalSeconds(PlanSaveDto row) {
        QualityControlTypeEnum qcEnum = QualityControlTypeEnum.valueOf(row.getQcType().trim().toUpperCase());
        QcExecutionRequest req = QcExecutionRequest.builder()
                .qcType(row.getQcType())
                .instruments(row.getInstruments())
                .concentrationPpb(row.getConcentrationPpb())
                .flowRateLpm(row.getFlowRateLpm())
                .durationOverrides(row.getDurationOverrides())
                .calibrationPolicy(row.getCalibrationPolicy())
                .build();
        Map<String, Object> flowParams = QcmExecutionOrchestrator.buildFlowParams(req, qcEnum);
        EnvCalibrationComposerIntegration composer = (EnvCalibrationComposerIntegration) composer();
        String gas = LogicDeviceBindingIds.composerGasKeyFromParameterName(row.getInstruments().get(0));
        List<PhaseInfo> phases = composer.estimatePhases(
                ExecutorType.getEnum(qcEnum.getClassName()), gas, flowParams.isEmpty() ? null : flowParams);
        long total = 0L;
        for (PhaseInfo phase : phases) {
            if (phase != null) {
                total += phase.getEstimatedSeconds();
            }
        }
        return total;
    }

    /** 返回 Object（签名不得引用 composer 类型：Spring 内省在 ruoyi 类加载器下解析签名会 CNFE），调用点体内强转。 */
    private Object composer() {
        return core.getIntegrationRegistry().getIntegration(COMPOSER_INTEGRATION_ID);
    }
}
