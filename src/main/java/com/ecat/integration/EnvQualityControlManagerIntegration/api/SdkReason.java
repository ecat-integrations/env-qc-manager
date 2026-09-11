package com.ecat.integration.EnvQualityControlManagerIntegration.api;

/**
 * SDK 受理/拒绝原因词汇（闭域，§3 域 5）：trigger 与 stop 回执的 reason 通道。受理也显式给
 * {@link #ACCEPTED}（R5）——消除「accepted=true 时 reason 到底是 null 还是有值」的双通道歧义，
 * 消费方 switch 全域即可穷尽，不再对照文档猜 String 常量。
 *
 * @author coffee
 */
public enum SdkReason {

    /** 触发已受理（accepted=true）：执行异步落 qcm_record，终态经 queryExecution 轮询 */
    ACCEPTED,

    /** 触发拒绝：执行器被互斥闸占用（本批次已写 FAILED 终态留痕） */
    BUSY_CONFLICT,

    /** 触发拒绝：请求 allowQueue=true，但 SDK 触发不支持排队；参数完整时本批次已写 FAILED 终态留痕 */
    QUEUE_NOT_SUPPORTED,

    /** 拒绝：请求参数非法（复用计划参数校验器同一套规则；触发侧参数完整时已写 FAILED 终态留痕） */
    INVALID_PARAM,

    /** 触发拒绝：执行器类型/结果格式化器未就绪（集成尚未完成启动注册，本批次已写 FAILED 终态留痕） */
    EXECUTOR_TYPE_NOT_READY,

    /** 停止已受理（accepted=true）：STOPPING 已置位，设备恢复与终态落库异步完成 */
    STOP_INITIATED,

    /** 停止拒绝：目标批次已结算（已终态或已在终止中），本次停止不受理且不改库——
     *  已终态行的 end_time/result_evaluation 是执行事实（幂等语义，重复 stop 不产生副作用） */
    ALREADY_TERMINAL,

    /** 停止拒绝：当前没有运行中的质控执行（非故障语义——「不管在跑什么都停」在没有东西可停时是正常结果） */
    NOTHING_RUNNING,

    /** 停止拒绝：寻址句柄解析不到任何质控记录（记录不存在或已清理） */
    RECORD_NOT_FOUND
}
