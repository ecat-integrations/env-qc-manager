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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Step 4 装配用例：plan 行 → QcExecutionRequest 字段全量搬运。 */
class ScheduledPlanFireActionTest {

    private QcmPlanMapper planMapper;
    private QcmExecutionOrchestrator orchestrator;
    private ScheduledPlanFireAction action;

    @BeforeEach
    void setUp() {
        planMapper = mock(QcmPlanMapper.class);
        orchestrator = mock(QcmExecutionOrchestrator.class);
        action = new ScheduledPlanFireAction(planMapper, orchestrator);
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
    }
}
