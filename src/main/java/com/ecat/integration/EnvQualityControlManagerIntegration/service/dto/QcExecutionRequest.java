package com.ecat.integration.EnvQualityControlManagerIntegration.service.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 统一触发入参（FR-02-01）：三源（调度器/页面/SDK）组装为同一请求交给 {@code QcmExecutionOrchestrator}。
 *
 * <p>qcType 为 {@code QualityControlTypeEnum.name}（snake_case，与 qcm_plan.qc_type 同词汇）；
 * 单仪器类型 instruments 恒长度 1（2.4 Validator / ScheduleSpecs 已保证，编排器不重复判类型×仪器矩阵）。</p>
 */
@Value
@Builder
public class QcExecutionRequest {

    /** 关联 qcm_plan.id；SDK 直接触发不经计划时为 null */
    Long planId;

    /** 质控类型 name（如 zero_check / span_check / audit_span_check） */
    String qcType;

    /** 仪器代码（ParameterEnum name）；多仪器零点 N 个（Phase 4），单仪器类型恒 1 */
    List<String> instruments;

    /** 标气浓度 ppb；span/人工核查类有效（人工核查传入时已是 ppb = ppm×1000） */
    BigDecimal concentrationPpb;

    /** 线性/准确度量程百分比序列；null=走 composer 默认序列 */
    List<Float> pointPercents;

    /** 标气流量 L/min；null=走 composer 默认 */
    BigDecimal flowRateLpm;

    /** 用户覆盖的时长参数（稀疏）：stableTimeSeconds / sampleCount / sampleIntervalSeconds 等，key 沿用 composer flowParams 契约 */
    Map<String, Object> durationOverrides;

    /** 触发时的计划配置快照 JSON（名称/类型/仪器/参数摘要）；计划删除后记录仍可溯源（FR-04-09） */
    String planSnapshotJson;
}
