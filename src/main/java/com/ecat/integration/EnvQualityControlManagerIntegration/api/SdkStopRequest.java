package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

/**
 * SDK 停止请求（§5）：批次粒度停止——单飞语义下一个批次一个 flow，寻址到批内任一行即停整批。
 * 寻址三选一（即 SdkTriggerReply 回给外部的三个句柄）或 allRunning；四个寻址位全空=INVALID_PARAM。
 */
@Value
@Builder
public class SdkStopRequest {

    /** 按记录句柄寻址（批内任一行，内部统一解析到批次）；与 batchId/triggerRequestId/allRunning 互斥给一个 */
    Long recordId;

    /** 按批次句柄寻址（SdkTriggerReply.batchId）；与 recordId/triggerRequestId/allRunning 互斥给一个 */
    String batchId;

    /** 按触发请求句柄寻址（SdkTriggerReply.triggerRequestId，受理→轮询→结果全程同一标识）；
     *  与 recordId/batchId/allRunning 互斥给一个 */
    String triggerRequestId;

    /** 「不管在跑什么都停」：单飞语义下=停当前唯一运行批次；无运行任务返回 NOTHING_RUNNING（非报错）。
     *  置 true 时其余寻址字段忽略 */
    boolean allRunning;

    /** 操作者（必填，来源契约 §6）：停止留痕落目标批次行 updated_by 与 result_evaluation 文案 */
    SdkOperator operator;
}
