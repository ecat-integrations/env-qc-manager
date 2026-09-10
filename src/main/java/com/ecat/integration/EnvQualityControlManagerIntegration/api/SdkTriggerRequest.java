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

    /** 质控类型（qcm_plan.qc_type 同词汇，如 zero_check / span_check / audit_span_check） */
    String qcType;

    /** 仪器代码列表（单仪器类型恒 1 台，如 SO2/NO2/CO/O3） */
    List<String> instruments;

    /** 标气浓度 ppb（span_check / audit_span_check 必填 >0） */
    BigDecimal concentrationPpb;

    /** 量程百分比序列（0~1 严格升序 ≥2 项；仅 multi_check / accuracy_check 可用） */
    List<Float> pointPercents;

    /** 标气流量 L/min（zero_check 拒绝提供；其余 ∈(0,50] 或省略） */
    BigDecimal flowRateLpm;

    /** 阶段时长覆盖（稀疏；key 白名单与计划参数表 14 键同词汇，值正整数秒） */
    Map<String, Number> durationOverrides;

    /** 触发操作者（必填且 name 必填，来源契约 §6）：存储侧拼平 displayOperator
     *  落 qcm_record.trigger_user，拒绝时留痕可溯源 */
    SdkOperator operator;

    /** 是否允许排队等待（必填；当前不支持排队，true 直接拒绝 QUEUE_NOT_SUPPORTED，FR-03-07） */
    boolean allowQueue;
}
