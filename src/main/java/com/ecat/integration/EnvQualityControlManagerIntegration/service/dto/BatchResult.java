package com.ecat.integration.EnvQualityControlManagerIntegration.service.dto;

import lombok.Value;

import java.util.List;

/**
 * 统一触发编排结果：ACCEPTED=已受理（执行结果异步落 qcm_record）；
 * REJECTED_BUSY_CONFLICT=互斥闸拒绝（执行器已被占用，N 条记录已写 FAILED 终态留痕，D10）；
 * REJECTED_EXECUTOR_TYPE_NOT_READY=执行器类型未接线（composer flow 缺失，N 条记录已写
 * FAILED+EXECUTOR_TYPE_NOT_READY 终态留痕，与 BUSY 同构，FR-02-14 中间态）；
 * REJECTED_PRE_TRIGGER=触发前拒绝留痕（参数完整但非法/排队不支持，未经互斥闸与 composer，
 * N 条记录已写 FAILED 终态留痕，行内留痕矩阵 §7）。
 */
@Value
public class BatchResult {

    public enum Status { ACCEPTED, REJECTED_BUSY_CONFLICT, REJECTED_EXECUTOR_TYPE_NOT_READY,
        REJECTED_PRE_TRIGGER }

    Status status;

    /** 批次标识（本次触发产生的 N 条 qcm_record 共享） */
    String batchId;

    /** 批次内记录 id 列表 */
    List<Long> recordIds;

    /** 触发请求标识（§4.0.1：受理时生成 UUID 并落每条 record.trigger_request_id；SDK 受理→轮询→结果全程同一标识） */
    String triggerRequestId;

    /** 拒绝原因（结构化枚举名，如 EXECUTOR_BUSY_CONFLICT）；ACCEPTED 时为 null */
    String failureReason;

    /** 兼容旧两参调用（无独立触发标识语义的场景）；编排器主路径走三参版本。 */
    public static BatchResult accepted(String batchId, List<Long> recordIds) {
        return accepted(batchId, recordIds, null);
    }

    public static BatchResult accepted(String batchId, List<Long> recordIds, String triggerRequestId) {
        return new BatchResult(Status.ACCEPTED, batchId, recordIds, triggerRequestId, null);
    }

    public static BatchResult rejectedBusyConflict(String batchId, List<Long> recordIds) {
        return rejectedBusyConflict(batchId, recordIds, null);
    }

    public static BatchResult rejectedBusyConflict(String batchId, List<Long> recordIds, String triggerRequestId) {
        return new BatchResult(Status.REJECTED_BUSY_CONFLICT, batchId, recordIds, triggerRequestId,
                "EXECUTOR_BUSY_CONFLICT");
    }

    public static BatchResult rejectedExecutorTypeNotReady(String batchId, List<Long> recordIds) {
        return rejectedExecutorTypeNotReady(batchId, recordIds, null);
    }

    public static BatchResult rejectedExecutorTypeNotReady(String batchId, List<Long> recordIds,
                                                           String triggerRequestId) {
        return new BatchResult(Status.REJECTED_EXECUTOR_TYPE_NOT_READY, batchId, recordIds, triggerRequestId,
                "EXECUTOR_TYPE_NOT_READY");
    }

    /** 触发前拒绝留痕（拒绝原因由调用方给：INVALID_PARAM / QUEUE_NOT_SUPPORTED）。 */
    public static BatchResult rejectedPreTrigger(String batchId, List<Long> recordIds,
                                                 String triggerRequestId, String failureReason) {
        return new BatchResult(Status.REJECTED_PRE_TRIGGER, batchId, recordIds, triggerRequestId, failureReason);
    }
}
