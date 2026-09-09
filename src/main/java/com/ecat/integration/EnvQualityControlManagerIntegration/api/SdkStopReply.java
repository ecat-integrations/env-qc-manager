package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * SDK 停止回复（§5）：受理即异步（accepted=true 表示已发起停止+STOPPING 置位，
 * 设备恢复与终态落库异步完成，终态经 queryExecution 轮询）。回执不哑停——
 * 即使 accepted=false 也回带目标批次标识与参数，调用方可辨「停了什么/为何没停」。
 */
@Value
@Builder
public class SdkStopReply {

    /** 是否已受理停止；false 时 reason 给出结构化原因（STOP_INITIATED / ALREADY_TERMINAL /
     *  NOTHING_RUNNING / RECORD_NOT_FOUND / INVALID_PARAM） */
    boolean accepted;

    /** 受理结果结构化原因（QualityControlSdk 的 REASON_* 常量词汇）；accepted=true 时为 STOP_INITIATED */
    String reason;

    /** 人读消息（含拒绝原因细节或目标批次说明） */
    String message;

    /** 目标批次标识（解析到批次时非空——含 ALREADY_TERMINAL；未解析到目标时为 null） */
    String batchId;

    /** 目标批次内记录 id 列表（解析到批次时非空） */
    List<Long> recordIds;

    /** 目标批次质控类型（解析到批次时非空） */
    String qcType;

    /** 目标批次受检仪器列表（解析到批次时非空） */
    List<String> instruments;

    /** 目标批次开始时刻（解析到批次时非空） */
    Instant startTime;
}
