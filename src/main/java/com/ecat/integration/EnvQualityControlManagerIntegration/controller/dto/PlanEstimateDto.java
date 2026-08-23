package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 阶段预估请求体（FR-01-32）：编辑中的参数值实时预估各阶段时长，只读、不落库、不触发。
 * 参数与执行同源——service 组装为 flowParams 后交 composer 只读预估能力。
 *
 * @author coffee
 */
@Data
public class PlanEstimateDto {

    /** 质控类型 name（QualityControlTypeEnum） */
    private String qcType;

    /** 仪器代码（单仪器类型 1 台，用于定位气路设备） */
    private List<String> instruments;

    /** 标气浓度 ppb（可空 = 默认） */
    private BigDecimal concentrationPpb;

    /** 标气流量 L/min（可空 = 默认） */
    private BigDecimal flowRateLpm;

    /** 量程百分比序列（可空 = composer 默认序列） */
    private List<Float> pointPercents;

    /** 阶段时长覆盖（稀疏，同 PlanSaveDto 白名单） */
    private Map<String, Object> durationOverrides;
}
