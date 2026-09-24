package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Step 4 装配用例：plan 行 → QcExecutionRequest 字段全量搬运 + 同日让位接线（03 设计 §7.2）。
 *
 * @author coffee
 */
class ScheduledPlanFireActionTest {

    private QcmPlanMapper planMapper;
    private QcmExecutionOrchestrator orchestrator;
    private SameDaySuppressionJudge judge;
    private ScheduledPlanFireAction action;

    @BeforeEach
    void setUp() {
        planMapper = mock(QcmPlanMapper.class);
        orchestrator = mock(QcmExecutionOrchestrator.class);
        judge = mock(SameDaySuppressionJudge.class);
        action = new ScheduledPlanFireAction(planMapper, orchestrator, judge);
    }

    @Test
    void scheduledFireAction_buildsRequestFromPlan() {
        QcmPlan plan = new QcmPlan();
        plan.setId(5L);
        plan.setPlanName("日常零点");
        plan.setQcType("span_check");
        plan.setInstruments("[\"CO\"]");
        plan.setConcentrationPpb(new BigDecimal("400"));
        plan.setFlowRateLpm(new BigDecimal("4.0"));
        plan.setDurationOverrides("{\"stableTimeSeconds\":300}");
        when(planMapper.selectById(5L)).thenReturn(plan);

        action.fire(5L);

        ArgumentCaptor<QcExecutionRequest> captor = ArgumentCaptor.forClass(QcExecutionRequest.class);
        verify(orchestrator).triggerExecution(captor.capture(), eq(TriggerSource.SCHEDULED), eq("system"));
        QcExecutionRequest req = captor.getValue();
        assertEquals(Long.valueOf(5L), req.getPlanId());
        assertEquals("span_check", req.getQcType());
        assertEquals(Collections.singletonList("CO"), req.getInstruments());
        assertEquals(0, new BigDecimal("400").compareTo(req.getConcentrationPpb()));
        assertEquals(0, new BigDecimal("4.0").compareTo(req.getFlowRateLpm()));
        assertEquals(Integer.valueOf(300), req.getDurationOverrides().get("stableTimeSeconds"));
        assertTrue(req.getPlanSnapshotJson().contains("日常零点"));
        assertTrue(req.getPlanSnapshotJson().contains("span_check"));
        assertTrue(req.getPlanSnapshotJson().contains("CO"));
    }

    @Test
    void planMissing_orFinished_doesNotThrow() {
        when(planMapper.selectById(404L)).thenReturn(null);
        action.fire(404L);

        QcmPlan finished = new QcmPlan();
        finished.setId(6L);
        finished.setStatus("FINISHED");
        when(planMapper.selectById(6L)).thenReturn(finished);
        action.fire(6L);

        verify(orchestrator, never()).triggerExecution(any(QcExecutionRequest.class), any(TriggerSource.class), any());
        verify(orchestrator, never()).skipExecution(any(QcExecutionRequest.class), anyString());
    }

    // ---------- 同日让位接线（03 设计 §7.2：过 misfire/指纹闩后、受理前） ----------

    @Test
    void lowSuppressedRowSkipsInsteadOfTriggering() {
        QcmPlan plan = new QcmPlan();
        plan.setId(7L);
        plan.setPlanName("日常-SO2-跨度");
        plan.setQcType("span_check");
        plan.setInstruments("[\"SO2\"]");
        plan.setSameDayPriority("LOW");
        when(planMapper.selectById(7L)).thenReturn(plan);
        when(judge.suppressedBy(plan)).thenReturn(Optional.of("周核查-SO2-跨度"));

        action.fire(7L);

        ArgumentCaptor<QcExecutionRequest> captor = ArgumentCaptor.forClass(QcExecutionRequest.class);
        verify(orchestrator).skipExecution(captor.capture(), eq("周核查-SO2-跨度"));
        assertEquals("span_check", captor.getValue().getQcType());
        verify(orchestrator, never()).triggerExecution(any(QcExecutionRequest.class), any(TriggerSource.class), any());
    }

    @Test
    void lowNotSuppressedProceedsToTrigger() {
        QcmPlan plan = new QcmPlan();
        plan.setId(8L);
        plan.setPlanName("日常-SO2-跨度");
        plan.setQcType("span_check");
        plan.setInstruments("[\"SO2\"]");
        plan.setSameDayPriority("LOW");
        when(planMapper.selectById(8L)).thenReturn(plan);
        when(judge.suppressedBy(plan)).thenReturn(Optional.empty());

        action.fire(8L);

        verify(orchestrator).triggerExecution(any(QcExecutionRequest.class), eq(TriggerSource.SCHEDULED), eq("system"));
        verify(orchestrator, never()).skipExecution(any(QcExecutionRequest.class), anyString());
    }

    @Test
    void nonLowRowDoesNotConsultJudge() {
        // NONE/HIGH/未标记不让位：判定短路在 judge 内部，接线层以 priority 前置守卫免起调用
        when(planMapper.selectById(5L)).thenReturn(spanPlanWithPriority(null));
        action.fire(5L);
        verify(judge, never()).suppressedBy(any(QcmPlan.class));

        when(planMapper.selectById(5L)).thenReturn(spanPlanWithPriority("HIGH"));
        action.fire(5L);
        verify(judge, never()).suppressedBy(any(QcmPlan.class));

        verify(orchestrator, times(2)).triggerExecution(any(QcExecutionRequest.class), eq(TriggerSource.SCHEDULED), eq("system"));
        verify(orchestrator, never()).skipExecution(any(QcExecutionRequest.class), anyString());
    }

    private static QcmPlan spanPlanWithPriority(String priority) {
        QcmPlan plan = new QcmPlan();
        plan.setId(5L);
        plan.setPlanName("城区站零点计划");
        plan.setQcType("span_check");
        plan.setInstruments("[\"SO2\"]");
        plan.setSameDayPriority(priority);
        return plan;
    }
}
