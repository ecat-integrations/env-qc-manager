package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * SDK 触发请求（FR-03-01..09 参数域）。字段语义与计划参数表一致，
 * 合法性由 qcm 内部同一校验器判定（FR-03-17），非法逐字段报错不静默修正。
 *
 * @author coffee
 */
@Value
@Builder
public class SdkTriggerRequest {

    /** 质控类型 */
    SdkQcType qcType;

    /** 受检仪器列表（单仪器类型恒 1 台） */
    List<SdkInstrument> instruments;

    /** 标气浓度 ppb（span_check / audit_span_check 必填 >0） */
    BigDecimal concentrationPpb;

    /** 量程百分比序列（0~1 严格升序 ≥2 项；仅 multi_check / accuracy_check 可用） */
    List<Float> pointPercents;

    /** 标气流量 L/min（zero_check 拒绝提供；其余 ∈(0,50] 或省略） */
    BigDecimal flowRateLpm;

    /** 阶段时长覆盖（稀疏；键为计划参数表 14 键白名单，整数键正整数秒/次数，百分比键为序列） */
    Map<SdkDurationKey, Number> durationOverrides;

    /** 触发操作者（必填且 name 必填，来源契约 §6）：存储侧拼平 displayOperator
     *  落 qcm_record.trigger_user，拒绝时留痕可溯源 */
    SdkOperator operator;

    /** 是否允许排队等待（必填；当前不支持排队，true 直接拒绝 QUEUE_NOT_SUPPORTED，FR-03-07） */
    boolean allowQueue;
}
