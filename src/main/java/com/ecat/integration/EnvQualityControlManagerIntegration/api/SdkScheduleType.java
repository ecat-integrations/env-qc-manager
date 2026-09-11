package com.ecat.integration.EnvQualityControlManagerIntegration.api;

/**
 * SDK 调度类型词汇（闭域，§3 域 9）：质控计划的调度节奏，与内部调度器同词汇。
 * 计划行 schedule_type 列的解析互译收口在 SDK 实现装配边界，坏行按既有约定整条跳过。
 *
 * @author coffee
 */
public enum SdkScheduleType {

    /** 每日固定时刻触发（hour/minute） */
    DAILY,

    /** 每周选中星期固定时刻触发（weekdays 1=周一..7=周日） */
    WEEKLY,

    /** 每月选中日固定时刻触发（monthDays 1..31，当月无该日则跳过该月） */
    MONTHLY,

    /** 一次性指定时刻触发（onceAt） */
    ONCE
}
