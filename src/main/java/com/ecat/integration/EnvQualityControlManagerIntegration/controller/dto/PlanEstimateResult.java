package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import lombok.Value;

import java.util.List;

/**
 * 阶段预估结果（FR-01-32）：各阶段清单 + 总预估 + 恢复阶段秒数（固定 120 提示）
 * + 浓度点清单（G-REQ-9：多点/准确度逐点、零点/跨度单点）。
 * 数据源为 composer 只读预估（同源公式，禁止 qcm 复制）。
 *
 * @author coffee
 */
@Value
public class PlanEstimateResult {

    /** 阶段（id / 显示名 / 预估秒），含恢复阶段 */
    List<PhaseEstimate> phases;

    /** 总预估秒数（全部阶段求和，含恢复） */
    long totalEstimatedSeconds;

    /** 恢复阶段秒数（AbstractCalibrationFlow 统一 120） */
    int recoverySeconds;

    /** 浓度点清单（多点/准确度逐点；零点/跨度单点；其余类型空表） */
    List<PointEstimate> points;

    @Value
    public static class PhaseEstimate {
        String id;
        String displayName;
        int estimatedSeconds;
    }

    /**
     * 浓度点：percent 为量程百分比（0~1，绝对浓度场景无百分语义时为 null，
     * 如跨度检查的绝对标气浓度）；displayValue/unit 由后端统一换算
     * （CO → ppm，其余 → ppb）。
     */
    @Value
    public static class PointEstimate {
        Double percent;
        double concentrationPpb;
        String displayValue;
        String unit;
    }
}
