package com.ecat.integration.EnvQualityControlManagerIntegration.service.dto;

import lombok.Value;

import java.util.List;

/**
 * 统一停止编排结果（设计 §8 核心上移）：REST 与 SDK 两面共用同一决策链，本类型是两面的翻译源——
 * REST 取 status+message 映射 200/400+msg，SDK 取 status 映射 REASON_* 词汇、
 * batchId/recordIds 作回执定位（批次业务字段由 SDK 面按 batchId 读记录行补齐）。
 * message 与既有 REST 停止文案逐字一致（B 已立的语义，两面不得分叉）。
 */
@Value
public class StopOutcome {

    public enum Status {
        /** 已受理停止：flow 已 stop + 批次 STOPPING 置位（终态由编排器回调落库），
         *  或无运行执行器时 set-based 直接收敛终止终态。 */
        INITIATED,
        /** 未受理：批次已结算（已终态/已在终止中），守卫 SQL 0 行受影响，不动库。 */
        ALREADY_SETTLED,
        /** 未受理：allRunning 寻址且当前无运行批次（非故障）。 */
        NOTHING_RUNNING,
        /** 未受理：寻址句柄解析不到任何记录。 */
        NOT_FOUND,
        /** 未受理：寻址字段全空或多键同传（语义含糊）。 */
        INVALID_PARAM
    }

    Status status;

    /** 人读消息（与既有 REST 停止回执文案一致；SDK 面可直接透传作 reply.message） */
    String message;

    /** 已解析到的批次标识（NOTHING_RUNNING / NOT_FOUND / INVALID_PARAM 时为 null） */
    String batchId;

    /** 已解析到的批次内记录 id 列表（无批次时为 null） */
    List<Long> recordIds;

    public static StopOutcome initiated(String message, String batchId, List<Long> recordIds) {
        return new StopOutcome(Status.INITIATED, message, batchId, recordIds);
    }

    public static StopOutcome alreadySettled(String message, String batchId, List<Long> recordIds) {
        return new StopOutcome(Status.ALREADY_SETTLED, message, batchId, recordIds);
    }

    public static StopOutcome nothingRunning(String message) {
        return new StopOutcome(Status.NOTHING_RUNNING, message, null, null);
    }

    public static StopOutcome notFound(String message) {
        return new StopOutcome(Status.NOT_FOUND, message, null, null);
    }

    public static StopOutcome invalidParam(String message) {
        return new StopOutcome(Status.INVALID_PARAM, message, null, null);
    }
}
