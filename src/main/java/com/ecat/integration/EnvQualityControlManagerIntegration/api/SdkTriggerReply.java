package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * SDK 触发回复（FR-03-10 毫秒级受理/拒绝）。
 * accepted=true 时 batchId/recordIds 非空且 reason={@link SdkReason#ACCEPTED}；
 * 拒绝时 reason 为对应的结构化原因（受理/拒绝原因全域见 {@link SdkReason}）。
 *
 * @author coffee
 */
@Value
@Builder
public class SdkTriggerReply {

    /** 是否受理（拒绝含互斥闸冲突、参数完整但非法/排队不支持——这些场景记录均已写 FAILED 终态留痕） */
    boolean accepted;

    /** 批次标识（本次触发产生的 N 条记录共享；仅请求为空/残缺（参数不可解析）或操作者缺失时为 null） */
    String batchId;

    /** 批次内记录 id 列表（受理与留痕拒绝时非空） */
    List<Long> recordIds;

    /** 触发请求标识（§4.0.1：受理→轮询→结果全程同一标识；受理与留痕拒绝时非空） */
    String triggerRequestId;

    /** 受理/拒绝的结构化原因（受理=ACCEPTED，拒绝=对应拒绝项，全域见 {@link SdkReason}） */
    SdkReason reason;

    /** 人读消息（含逐字段校验错误清单或编排器透传信息） */
    String message;

    public static SdkTriggerReply rejected(SdkReason reason, String message) {
        return SdkTriggerReply.builder()
                .accepted(false)
                .reason(reason)
                .message(message)
                .build();
    }
}
