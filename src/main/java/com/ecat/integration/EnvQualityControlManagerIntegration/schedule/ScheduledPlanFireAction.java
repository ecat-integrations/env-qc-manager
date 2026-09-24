package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.PlanRequestAssembler;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 计划到点触发动作（Step 4，FR-02-01 SCHEDULED 源接线）：QcmPlanScheduler 到点只交 planId，
 * 本类自查 qcm_plan 行经 {@link PlanRequestAssembler}（与 MANUAL runNow 共用的唯一装配路径）
 * 装配执行请求，交统一编排器执行。
 *
 * <p>同日优先级让位在过 misfire/指纹闩后、受理前判定：仅 LOW 行咨询
 * {@link SameDaySuppressionJudge}，被覆盖则改走 skipExecution 留痕（整行 SKIPPED，不调 composer）。
 * MANUAL 立即执行与 SDK REMOTE 触发不走本类，天然不压人也不让位。</p>
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
    private final SameDaySuppressionJudge suppressionJudge;

    @Autowired
    public ScheduledPlanFireAction(QcmPlanMapper planMapper, QcmExecutionOrchestrator orchestrator,
                                   SameDaySuppressionJudge suppressionJudge) {
        this.planMapper = planMapper;
        this.orchestrator = orchestrator;
        this.suppressionJudge = suppressionJudge;
    }

    @Override
    public void fire(Long planId) {
        QcmPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            log.warn("qcm 计划 {} 到点触发时行已不存在，跳过", planId);
            return;
        }
        if ("FINISHED".equals(plan.getStatus())) {
            log.warn("qcm 计划 {}({}) 已 FINISHED，跳过到点触发", planId, plan.getPlanName());
            return;
        }
        QcExecutionRequest request = PlanRequestAssembler.assemble(plan);
        if ("LOW".equals(plan.getSameDayPriority())) {
            Optional<String> suppressor = suppressionJudge.suppressedBy(plan);
            if (suppressor.isPresent()) {
                log.info("qcm 计划 {}({}) 当日被高优先级计划「{}」覆盖，让位留痕 SKIPPED",
                        planId, plan.getPlanName(), suppressor.get());
                orchestrator.skipExecution(request, suppressor.get());
                return;
            }
        }
        orchestrator.triggerExecution(request, TriggerSource.SCHEDULED, "system");
    }
}
