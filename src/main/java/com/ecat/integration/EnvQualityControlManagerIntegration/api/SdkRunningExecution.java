package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * 当前运行中的质控执行快照（§5 queryRunning 返回项）：单飞语义下至多 1 项，
 * 列表形态防未来并发策略变化。外部「先看在跑什么再停」的入口——
 * 其 batchId/recordIds/triggerRequestId 即 SdkStopRequest 的三个寻址句柄。
 *
 * @author coffee
 */
@Value
@Builder
public class SdkRunningExecution {

    /** 运行批次标识（可直接作为 SdkStopRequest.batchId） */
    String batchId;

    /** 批次内记录 id 列表（每台受检仪器一行，可任取一行作为 SdkStopRequest.recordId） */
    List<Long> recordIds;

    /** 触发请求标识（可直接作为 SdkStopRequest.triggerRequestId） */
    String triggerRequestId;

    /** 质控类型 */
    SdkQcType qcType;

    /** 受检仪器列表（单仪器类型恒 1 台） */
    List<SdkInstrument> instruments;

    /** 批次开始时刻 */
    Instant startTime;

    /** 触发来源 */
    SdkTriggerSource triggerSource;

    /** 触发者（触发时留痕的 displayOperator：PLATFORM 形态为 name@ip[:port]） */
    String triggerUser;
}
