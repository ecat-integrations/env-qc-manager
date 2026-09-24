package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

/**
 * 质控计划调度类型（调度器唯一真相源）。
 * <ul>
 *   <li>DAILY：每日固定时刻触发。存量类型——统一调度模型后新 UI 不再产出（按天语义走
 *       INTERVAL intervalDays=1），API/SDK 仍接受（行为不动），展示层译作「每 1 天」</li>
 *   <li>WEEKLY：选中星期（weekdays，1=周一..7=周日）固定时刻触发，可隔 intervalWeeks 周
 *       （1..52；周相位锚=含 anchorDate 的周，>1 时须提供）</li>
 *   <li>MONTHLY：每月选中日（monthDays，1..31；当月无该日则跳过该月）固定时刻触发</li>
 *   <li>ONCE：一次性指定时刻（onceAt）触发</li>
 *   <li>INTERVAL：每 intervalDays 天（1..31）于固定时刻触发，锚点日 anchorDate 起算
 *       （=有效期起 planStartTime 的墙钟日，保存侧派生写入 config，不再隐式取保存当日）</li>
 * </ul>
 *
 * @author coffee
 */
public enum ScheduleType {
    DAILY, WEEKLY, MONTHLY, ONCE, INTERVAL
}
