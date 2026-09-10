package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.PlanRequestAssembler;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 计划到点触发动作（Step 4，FR-02-01 SCHEDULED 源接线）：QcmPlanScheduler 到点只交 planId，
 * 本类自查 qcm_plan 行经 {@link PlanRequestAssembler}（与 MANUAL runNow 共用的唯一装配路径）
 * 装配执行请求，交统一编排器执行。
 *
 * <p>plan 不存在/已 FINISHED → log.warn 返回（不 throw，不惊扰调度器状态机）。</p>
 *
 * @author coffee
 */
@Service
public class ScheduledPlanFireAction implements PlanFireAction {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPlanFireAction.class);

    private final QcmPlanMapper planMapper;
    private final QcmExecutionOrchestrator orchestrator;

    @Autowired
    public ScheduledPlanFireAction(QcmPlanMapper planMapper, QcmExecutionOrchestrator orchestrator) {
        this.planMapper = planMapper;
        this.orchestrator = orchestrator;
    }

    @Override
    public void fire(Long planId) {
        QcmPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            log.warn("[诊断调试] qcm 计划 {} 到点触发时行已不存在，跳过", planId);
            return;
        }
        if ("FINISHED".equals(plan.getStatus())) {
            log.warn("[诊断调试] qcm 计划 {}({}) 已 FINISHED，跳过到点触发", planId, plan.getPlanName());
            return;
        }
        orchestrator.triggerExecution(PlanRequestAssembler.assemble(plan), TriggerSource.SCHEDULED, "system");
    }
}
