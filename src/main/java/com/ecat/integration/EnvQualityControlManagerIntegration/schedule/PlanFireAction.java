package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

/**
 * 计划到点触发动作（2.3 编排器接线的隔离缝）：调度器只认本接口，到点把 planId 交给编排器执行。
 *
 * <p>应用场景：调度器 fire 时调用；编排器（质控任务编排/重试）由后续任务提供实现并注册为 Spring bean，
 * 调度器经 ObjectProvider 按存在性发现——无实现时不假装执行成功，仅 warn 并推进状态机。</p>
 *
 * @author coffee
 */
@FunctionalInterface
public interface PlanFireAction {

    /**
     * 执行到点计划（planId 对应 qcm_plan 行的完整编排由实现方自查 DB）。
     *
     * @param planId 计划 ID
     */
    void fire(Long planId);
}
