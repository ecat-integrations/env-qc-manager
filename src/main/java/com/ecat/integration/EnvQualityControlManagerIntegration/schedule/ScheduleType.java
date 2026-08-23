package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

/**
 * 质控计划调度类型（调度器唯一真相源）。
 * <ul>
 *   <li>DAILY：每日固定时刻触发</li>
 *   <li>WEEKLY：每周选中星期（weekdays，1=周一..7=周日）固定时刻触发</li>
 *   <li>MONTHLY：每月选中日（monthDays，1..31；当月无该日则跳过该月）固定时刻触发</li>
 *   <li>ONCE：一次性指定时刻（onceAt）触发</li>
 * </ul>
 */
public enum ScheduleType {
    DAILY, WEEKLY, MONTHLY, ONCE
}
