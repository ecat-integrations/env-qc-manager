package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EnvCalibrationComposerIntegration.AbstractCalibrationFlow;
import com.ecat.integration.EnvCalibrationComposerIntegration.EnvCalibrationComposerIntegration;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorStoppedException;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorType;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseExecutionRecord;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.EnvQualityControlManagerIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordKeyParamMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPhaseMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPointMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.StopOutcome;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一触发编排器确定性用例：mock composer/registry/TaskExecutor，零 sleep——
 * future 用手动完成的 CompletableFuture 驱动回调，同步路径直接断言落库参数。
 *
 * @author coffee
 */
class QcmExecutionOrchestratorTest {

    private IQcmRecordService recordService;
    private QcmPlanMapper planMapper;
    private QcmRecordMapper recordMapper;
    private EcatCore core;
    private IntegrationRegistry registry;
    private EnvCalibrationComposerIntegration composer;
    private EnvQualityControlManagerIntegration entry;
    private QcResultFormatter formatter;
    private ResultSnapshotWriter snapshotWriter;
    private QcmExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        recordService = mock(IQcmRecordService.class);
        planMapper = mock(QcmPlanMapper.class);
        recordMapper = mock(QcmRecordMapper.class);
        core = mock(EcatCore.class);
        registry = mock(IntegrationRegistry.class);
        composer = mock(EnvCalibrationComposerIntegration.class);
        entry = new EnvQualityControlManagerIntegration();
        when(core.getIntegrationRegistry()).thenReturn(registry);
        when(registry.getIntegration("integration-env-calibration-composer")).thenReturn(composer);
        when(registry.getIntegration("integration-env-qc-manager")).thenReturn(entry);
        formatter = mock(QcResultFormatter.class);
        when(formatter.supports(anyString())).thenReturn(true);
        when(formatter.format(any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("{}");
        when(formatter.formatStub(any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("{}");
        snapshotWriter = mock(ResultSnapshotWriter.class);
        orchestrator = new QcmExecutionOrchestrator(recordService, planMapper, recordMapper, core, snapshotWriter);
        orchestrator.setFormatters(Collections.singletonList(formatter));
        // 模拟 insertBatch 的 useGeneratedKeys id 回填（生产由 DB 驱动回填）
        when(recordService.insertQcmRecordBatch(any(List.class))).thenAnswer(inv -> {
            List<QcmRecord> inserted = inv.getArgument(0);
            for (int i = 0; i < inserted.size(); i++) {
                inserted.get(i).setId(100L + i);
            }
            return inserted.size();
        });
    }

    private QcExecutionRequest.QcExecutionRequestBuilder singleInstrumentRequest() {
        return QcExecutionRequest.builder()
                .qcType("zero_check")
                .instruments(Collections.singletonList("SO2"));
    }

    @SuppressWarnings("unchecked")
    private List<QcmRecord> captureInsertedBatch(int expectedSize) {
        ArgumentCaptor<List<QcmRecord>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(recordService).insertQcmRecordBatch(captor.capture());
        assertEquals(expectedSize, captor.getValue().size());
        return captor.getValue();
    }

    private void stubIdleAndRunningFlow() {
        when(composer.isRunning()).thenReturn(false);
        when(composer.getRunningExecutor()).thenReturn(mock(AbstractCalibrationFlow.class));
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class)))
                .thenReturn(new CompletableFuture<>());
        when(composer.execute(any(ExecutorType.class), anyString()))
                .thenReturn(new CompletableFuture<>());
    }

    @Test
    void threeSources_convergeToSameEntry() {
        when(composer.isRunning()).thenReturn(true);
        for (TriggerSource source : TriggerSource.values()) {
            BatchResult result = orchestrator.triggerExecution(
                    singleInstrumentRequest().build(), source, "u-" + source.name());
            assertEquals(BatchResult.Status.REJECTED_BUSY_CONFLICT, result.getStatus());
        }
        // 三源各自都到达互斥闸（isRunning 三次）且各自写记录
        verify(composer, times(3)).isRunning();
        verify(recordService, times(3)).insertQcmRecordBatch(any(List.class));
    }

    @Test
    void busyConflict_writesFailedRecordsForAllSources() {
        when(composer.isRunning()).thenReturn(true);
        for (TriggerSource source : TriggerSource.values()) {
            BatchResult result = orchestrator.triggerExecution(
                    singleInstrumentRequest().build(), source, "user");
            assertEquals(BatchResult.Status.REJECTED_BUSY_CONFLICT, result.getStatus());
            assertEquals("EXECUTOR_BUSY_CONFLICT", result.getFailureReason());
            assertEquals(1, result.getRecordIds().size());
        }
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        // 3 源 × 1 记录：每条被落终态 FAILED 一次
        verify(recordService, times(3)).updateQcmRecord(captor.capture());
        for (QcmRecord r : captor.getAllValues()) {
            assertEquals(ExecutionStatusEnum.FAILED.getCode().intValue(), r.getExecutionStatus());
            assertEquals("EXECUTOR_BUSY_CONFLICT", r.getFailureReason());
            assertNotNull(r.getEndTime());
        }
    }

    @Test
    void batchInsert_singleTransactionNRecords() {
        QcExecutionRequest req = QcExecutionRequest.builder()
                .qcType("zero_check")
                .instruments(java.util.Arrays.asList("SO2", "NO2", "CO"))
                .planSnapshotJson("{\"planName\":\"p\"}")
                .build();
        stubIdleAndRunningFlow();
        BatchResult result = orchestrator.triggerExecution(req, TriggerSource.MANUAL, "user");
        assertEquals(BatchResult.Status.ACCEPTED, result.getStatus());
        List<QcmRecord> inserted = captureInsertedBatch(3);
        for (QcmRecord r : inserted) {
            assertEquals(result.getBatchId(), r.getBatchId());
            // 捕获的是同一对象引用：插入时 WAITING，互斥闸通过后已推进为 RUNNING
            assertEquals(ExecutionStatusEnum.RUNNING.getCode().intValue(), r.getExecutionStatus());
            assertEquals(TriggerSource.MANUAL.getCode(), r.getTaskType());
            assertEquals("user", r.getTriggerUser());
            assertEquals("{\"planName\":\"p\"}", r.getRecordSnapshot());
        }
        // 单仪器 flow 只注册一次，但批次全部行都挂到 executorMap
        assertEquals(3, entry.executorMap.size());
        verify(composer, times(1)).execute(any(ExecutorType.class), anyString(), any(Map.class));
    }

    @Test
    void oncePlan_markedFinishedRegardlessOfOutcome() {
        QcmPlan plan = new QcmPlan();
        plan.setId(9L);
        plan.setScheduleType("ONCE");
        when(planMapper.selectById(9L)).thenReturn(plan);

        // 成功路径
        stubIdleAndRunningFlow();
        orchestrator.triggerExecution(singleInstrumentRequest().planId(9L).build(),
                TriggerSource.SCHEDULED, "system");
        verify(planMapper).updateStatus(9L, "FINISHED", "qcm-orchestrator");

        // 冲突路径
        when(composer.isRunning()).thenReturn(true);
        orchestrator.triggerExecution(singleInstrumentRequest().planId(9L).build(),
                TriggerSource.MANUAL, "user");
        verify(planMapper, times(2)).updateStatus(9L, "FINISHED", "qcm-orchestrator");

        // 启动失败路径
        when(composer.isRunning()).thenReturn(false);
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class)))
                .thenThrow(new IllegalArgumentException("launch failed"));
        orchestrator.triggerExecution(singleInstrumentRequest().planId(9L).build(),
                TriggerSource.REMOTE, "sdk");
        verify(planMapper, times(3)).updateStatus(9L, "FINISHED", "qcm-orchestrator");
    }

    @Test
    void lastFireTimeUpdatedForAllSources() {
        QcmPlan plan = new QcmPlan();
        plan.setId(7L);
        plan.setScheduleType("DAILY");
        when(planMapper.selectById(7L)).thenReturn(plan);
        when(composer.isRunning()).thenReturn(true);
        for (TriggerSource source : TriggerSource.values()) {
            orchestrator.triggerExecution(singleInstrumentRequest().planId(7L).build(), source, "u");
        }
        // 非 ONCE：不动 next_fire_time（用专用语句），所有源都更新 last_fire_time
        verify(planMapper, times(3)).updateLastFireTime(eq(7L), any(Instant.class), anyString());
        verify(planMapper, never()).updateNextFireTime(eq(7L), isNull(), any(Instant.class));
    }

    @Test
    void recordSnapshotPersisted() {
        when(composer.isRunning()).thenReturn(true);
        QcExecutionRequest req = QcExecutionRequest.builder()
                .qcType("span_check")
                .instruments(Collections.singletonList("CO"))
                .planSnapshotJson("{\"planName\":\"计划A\",\"qcType\":\"span_check\",\"instruments\":[\"CO\"]}")
                .build();
        orchestrator.triggerExecution(req, TriggerSource.SCHEDULED, "system");
        QcmRecord inserted = captureInsertedBatch(1).get(0);
        assertTrue(inserted.getRecordSnapshot().contains("计划A"));
        assertTrue(inserted.getRecordSnapshot().contains("span_check"));
        assertTrue(inserted.getRecordSnapshot().contains("CO"));
    }

    @Test
    void futureException_writtenAsFailedTerminalNoThrow() {
        stubIdleAndRunningFlow();
        CompletableFuture<com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase> future =
                new CompletableFuture<>();
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class))).thenReturn(future);
        BatchResult result = orchestrator.triggerExecution(
                singleInstrumentRequest().build(), TriggerSource.REMOTE, "sdk");
        assertEquals(BatchResult.Status.ACCEPTED, result.getStatus());
        // 回调异常：落库终态 FAILED，不向 future 再抛（G-BUG-4）
        future.completeExceptionally(new RuntimeException("boom"));
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        // 第一次是启动前 RUNNING 标记，第二次是异常终态
        verify(recordService, times(2)).updateQcmRecord(captor.capture());
        QcmRecord terminal = captor.getAllValues().get(1);
        assertEquals(ExecutionStatusEnum.FAILED.getCode().intValue(), terminal.getExecutionStatus());
        assertNotNull(terminal.getEndTime());
        assertTrue(terminal.getResultEvaluation().contains("校准任务过程异常"));
    }

    @Test
    void launchFailure_persistedAsFailedTerminal() {
        when(composer.isRunning()).thenReturn(false);
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class)))
                .thenThrow(new IllegalArgumentException("Unknown ExecutorType: unknow"));
        orchestrator.triggerExecution(
                singleInstrumentRequest().qcType("span_check")
                        .concentrationPpb(new BigDecimal("400"))
                        .build(), TriggerSource.MANUAL, "user");
        captureInsertedBatch(1);
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        // 第一次是 RUNNING 标记，第二次是启动失败终态
        verify(recordService, times(2)).updateQcmRecord(captor.capture());
        assertEquals(ExecutionStatusEnum.FAILED.getCode().intValue(),
                captor.getAllValues().get(1).getExecutionStatus());
        assertNotNull(captor.getAllValues().get(1).getEndTime());
    }

    /**
     * D-1：进程内互斥闸——composer 无内建互斥，调度/手动/SDK 并发触发时由编排器串行化，
     * 恰一个 ACCEPTED、一个 REJECTED_BUSY_CONFLICT。确定性：mock isRunning 由首次 execute 置位，
     * 闸内检查→启动原子后第二个触发必然看到 running=true。
     */
    @Test
    void concurrentTrigger_secondBlockedByInProcessMutex() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger started = new AtomicInteger();
        // isRunning 由 execute 调用置位（模拟 composer 真实行为：execute 后 running=true 直到完成）
        when(composer.isRunning()).thenAnswer(inv -> started.get() > 0);
        when(composer.getRunningExecutor()).thenAnswer(inv ->
                started.get() > 0 ? mock(AbstractCalibrationFlow.class) : null);
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class))).thenAnswer(inv -> {
            started.incrementAndGet();
            return new CompletableFuture<>();
        });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            final String user = "user-" + i;
            new Thread(() -> {
                try {
                    ready.countDown();
                    ready.await();
                    BatchResult r = orchestrator.triggerExecution(
                            singleInstrumentRequest().build(), TriggerSource.MANUAL, user);
                    if (r.getStatus() == BatchResult.Status.ACCEPTED) {
                        accepted.incrementAndGet();
                    } else if (r.getStatus() == BatchResult.Status.REJECTED_BUSY_CONFLICT) {
                        rejected.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发触发应有限时间内完成");
        if (failure.get() != null) {
            throw new AssertionError("并发触发抛异常", failure.get());
        }
        assertEquals(1, accepted.get(), "恰一个触发过闸 ACCEPTED");
        assertEquals(1, rejected.get(), "第二个触发被进程内互斥闸拒 BUSY_CONFLICT");
    }

    /** D-2：裸 ExecutorResultBase（非 CheckResult 子类）异常完成走 stub 落库路径，不走通用异常格式。 */
    @Test
    void bareExecutorResult_stubPathPreserved() {
        ExecutorResultBase bare = new ExecutorResultBase(false, true);
        bare.setErrorMessage("zero check aborted by device");
        bare.setPhaseRecords(Collections.singletonList(
                new PhaseExecutionRecord("p1", "阶段一", Instant.now(), Instant.now(), 10)));
        CompletableFuture<ExecutorResultBase> future = new CompletableFuture<>();
        when(composer.isRunning()).thenReturn(false);
        when(composer.getRunningExecutor()).thenReturn(mock(AbstractCalibrationFlow.class));
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class))).thenReturn(future);

        orchestrator.triggerExecution(singleInstrumentRequest().build(), TriggerSource.SCHEDULED, "system");
        future.complete(bare);

        // stub 通道被调用（phaseRecords 保留），非裸异常格式通道不被调用
        ArgumentCaptor<ExecutorResultBase> stubCaptor = ArgumentCaptor.forClass(ExecutorResultBase.class);
        verify(formatter, times(1)).formatStub(any(), any(), stubCaptor.capture(), anyString(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(formatter, never()).format(any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        assertEquals(1, stubCaptor.getValue().getPhaseRecords().size());

        ArgumentCaptor<QcmRecord> recordCaptor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordService, times(2)).updateQcmRecord(recordCaptor.capture());
        QcmRecord terminal = recordCaptor.getAllValues().get(1);
        assertEquals(ExecutionStatusEnum.FAILED.getCode().intValue(), terminal.getExecutionStatus());
        // execution_log 来自 stub 格式化（mock 返回 "{}"），非「执行异常」通用格式串
        assertEquals("{}", terminal.getExecutionLog());
        assertEquals("zero check aborted by device", terminal.getResultEvaluation());
    }

    // ---------- multi_zero_check 中间态：EXECUTOR_TYPE_NOT_READY（FR-02-14，Phase 4） ----------

    @Test
    void multiZeroCheck_rejectedNotReady_NFailedRecords_noComposerExecute() {
        QcExecutionRequest req = QcExecutionRequest.builder()
                .qcType("multi_zero_check")
                .instruments(java.util.Arrays.asList("SO2", "NO2", "CO", "O3"))
                .planSnapshotJson("{\"planName\":\"零点质控-全部仪器\"}")
                .build();
        BatchResult result = orchestrator.triggerExecution(req, TriggerSource.SCHEDULED, "system");
        assertEquals(BatchResult.Status.REJECTED_EXECUTOR_TYPE_NOT_READY, result.getStatus());
        assertEquals("EXECUTOR_TYPE_NOT_READY", result.getFailureReason());
        assertEquals(4, result.getRecordIds().size());
        // 闸前拒绝：不查互斥闸、不触达 composer execute
        verify(composer, never()).isRunning();
        verify(composer, never()).execute(any(ExecutorType.class), anyString());
        verify(composer, never()).execute(any(ExecutorType.class), anyString(), any(Map.class));
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordService, times(4)).updateQcmRecord(captor.capture());
        for (QcmRecord r : captor.getAllValues()) {
            assertEquals(ExecutionStatusEnum.FAILED.getCode().intValue(), r.getExecutionStatus());
            assertEquals("EXECUTOR_TYPE_NOT_READY", r.getFailureReason());
            assertEquals("多仪器零点 flow 未接线", r.getExecutionLog());
            assertNotNull(r.getEndTime());
        }
    }

    @Test
    void multiZeroCheck_oncePlanStillFinishes() {
        QcmPlan plan = new QcmPlan();
        plan.setId(21L);
        plan.setScheduleType("ONCE");
        when(planMapper.selectById(21L)).thenReturn(plan);
        QcExecutionRequest req = QcExecutionRequest.builder()
                .qcType("multi_zero_check")
                .instruments(Collections.singletonList("SO2"))
                .planId(21L)
                .build();
        BatchResult result = orchestrator.triggerExecution(req, TriggerSource.MANUAL, "user");
        assertEquals(BatchResult.Status.REJECTED_EXECUTOR_TYPE_NOT_READY, result.getStatus());
        verify(planMapper).updateStatus(21L, "FINISHED", "qcm-orchestrator");
    }

    @Test
    void multiZeroCheck_idleComposer_unaffected_zeroCheckRegression() {
        // 零点单仪器回归：NOT_READY 闸只拦 multi_zero_check，zero_check 照常受理
        stubIdleAndRunningFlow();
        BatchResult result = orchestrator.triggerExecution(
                singleInstrumentRequest().build(), TriggerSource.MANUAL, "user");
        assertEquals(BatchResult.Status.ACCEPTED, result.getStatus());
        verify(composer, times(1)).execute(eq(ExecutorType.ZERO_CHECK), anyString(), any(Map.class));
    }

    // ---------- buildFlowParams（G-BUG-16：zero/span 丢 durationOverrides） ----------

    @Test
    void buildFlowParamsZeroCheckIncludesDurationOverrides() {
        QcExecutionRequest req = singleInstrumentRequest()
                .durationOverrides(new java.util.LinkedHashMap<>())
                .build();
        req.getDurationOverrides().put("sampleCount", 20);
        req.getDurationOverrides().put("stableTimeSeconds", 600);
        Map<String, Object> flowParams = QcmExecutionOrchestrator.buildFlowParams(req,
                com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.ZERO_CHECK);
        assertEquals(0f, flowParams.get("spanConcentrationPpb"));
        assertEquals(20, flowParams.get("sampleCount"));
        assertEquals(600, flowParams.get("stableTimeSeconds"));
    }

    @Test
    void buildFlowParamsSpanCheckIncludesDurationOverrides() {
        QcExecutionRequest req = QcExecutionRequest.builder()
                .qcType("span_check")
                .instruments(Collections.singletonList("SO2"))
                .concentrationPpb(BigDecimal.valueOf(400))
                .durationOverrides(new java.util.LinkedHashMap<>())
                .build();
        req.getDurationOverrides().put("sampleCount", 20);
        req.getDurationOverrides().put("stableTimeSeconds", 600);
        Map<String, Object> flowParams = QcmExecutionOrchestrator.buildFlowParams(req,
                com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.SPAN_CHECK);
        assertEquals(400f, flowParams.get("spanConcentrationPpb"));
        assertEquals(20, flowParams.get("sampleCount"));
        assertEquals(600, flowParams.get("stableTimeSeconds"));
    }

    // ---------- §4.0.1 trigger_request_id + §4.0 完成时快照冻结 ----------

    @Test
    void triggerRequestId_generatedReturnedAndPersisted() {
        stubIdleAndRunningFlow();
        BatchResult result = orchestrator.triggerExecution(
                singleInstrumentRequest().build(), TriggerSource.REMOTE, "sdk");
        assertEquals(BatchResult.Status.ACCEPTED, result.getStatus());
        assertNotNull(result.getTriggerRequestId());
        List<QcmRecord> inserted = captureInsertedBatch(1);
        assertEquals(result.getTriggerRequestId(), inserted.get(0).getTriggerRequestId());
        // flow_type 受理时即可冻结（ExecutorType className，与 qc_type 正交）
        assertEquals("air.monitor.calibration.zero_check", inserted.get(0).getFlowType());
    }

    @Test
    void successCallback_freezesResultSnapshot() {
        String logJson = "{\"result\":{\"stdValue\":100.5,\"deviceValue\":101.2,\"resultValue\":0.7,"
                + "\"checkPassLimit\":2.0,\"checkCalibLimit\":10.0,\"isPass\":true},"
                + "\"qcPhaseTimelines\":[{\"phaseCode\":\"zero\",\"phaseName\":\"零点稳定\","
                + "\"estimatedSeconds\":300,\"startTimeMillis\":1700000000000,\"endTimeMillis\":1700000100000}],"
                + "\"keyParametersSnapshot\":[{\"tName\":\"主浓度\",\"tValue\":\"102.5 ppb\",\"tRange\":\"80~120\"}],"
                + "\"keyParametersSamplingWindow\":{\"startTimeMillis\":1700000000000,\"endTimeMillis\":1700000100000}}";
        when(formatter.format(any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(logJson);
        ExecutorResultBase pass = new ExecutorResultBase(true, false);
        CompletableFuture<ExecutorResultBase> future = new CompletableFuture<>();
        when(composer.isRunning()).thenReturn(false);
        when(composer.getRunningExecutor()).thenReturn(mock(AbstractCalibrationFlow.class));
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class))).thenReturn(future);

        long before = System.currentTimeMillis();
        orchestrator.triggerExecution(singleInstrumentRequest().build(), TriggerSource.MANUAL, "user");
        future.complete(pass);
        long after = System.currentTimeMillis();

        ArgumentCaptor<Map<String, Object>> judgementCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(snapshotWriter).freezeResultSnapshot(eq(100L), eq("air.monitor.calibration.zero_check"),
                judgementCaptor.capture(), any(List.class), any(List.class),
                eq(Instant.ofEpochMilli(1700000000000L)), eq(Instant.ofEpochMilli(1700000100000L)),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                isNull());
        Map<String, Object> judgement = judgementCaptor.getValue();
        assertEquals(100.5, ((Number) judgement.get("stdValue")).doubleValue(), 1e-9);
        assertEquals(Boolean.TRUE, judgement.get("isPass"));
    }

    @Test
    void successCallback_flowExecutionRefUsesTriggerTimeMillis() {
        String logJson = "{\"result\":{},\"statusMap\":{\"isPass\":true}}";
        when(formatter.format(any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(logJson);
        ExecutorResultBase pass = new ExecutorResultBase(true, false);
        CompletableFuture<ExecutorResultBase> future = new CompletableFuture<>();
        when(composer.isRunning()).thenReturn(false);
        when(composer.getRunningExecutor()).thenReturn(mock(AbstractCalibrationFlow.class));
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class))).thenReturn(future);

        long before = System.currentTimeMillis();
        orchestrator.triggerExecution(singleInstrumentRequest().build(), TriggerSource.MANUAL, "user");
        future.complete(pass);
        long after = System.currentTimeMillis();

        org.mockito.ArgumentCaptor<Long> millisCaptor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(snapshotWriter).freezeResultSnapshot(eq(100L), anyString(), any(Map.class),
                any(List.class), any(List.class), isNull(), isNull(), millisCaptor.capture(),
                org.mockito.ArgumentMatchers.anyString(), isNull());
        assertTrue(millisCaptor.getValue() >= before && millisCaptor.getValue() <= after,
                "flowStartMillis 应取受理时刻");
    }

    @Test
    void busyConflict_neverFreezesSnapshot() {
        when(composer.isRunning()).thenReturn(true);
        orchestrator.triggerExecution(singleInstrumentRequest().build(), TriggerSource.SCHEDULED, "system");
        verify(snapshotWriter, never()).freezeResultSnapshot(org.mockito.ArgumentMatchers.anyLong(),
                anyString(), any(Map.class), any(List.class), any(List.class),
                isNull(), isNull(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), isNull());
    }

    // ---------- persistRejectedBatch（行内留痕矩阵：触发前拒绝也建 FAILED 留痕行） ----------

    @Test
    void persistRejectedBatch_writesFailedTraceRowsForWholeBatch() {
        QcExecutionRequest req = QcExecutionRequest.builder()
                .qcType("multi_check")
                .instruments(Arrays.asList("SO2", "NO2"))
                .planSnapshotJson("{\"source\":\"REMOTE_SDK\",\"operator\":{\"name\":\"scada\"}}")
                .build();

        BatchResult result = orchestrator.persistRejectedBatch(req, TriggerSource.REMOTE,
                "scada@10.0.0.1:5025", "INVALID_PARAM", "标气浓度必填");

        assertEquals(BatchResult.Status.REJECTED_PRE_TRIGGER, result.getStatus());
        assertEquals("INVALID_PARAM", result.getFailureReason());
        assertNotNull(result.getBatchId());
        assertNotNull(result.getTriggerRequestId());
        List<QcmRecord> inserted = captureInsertedBatch(2);
        assertEquals(2, result.getRecordIds().size());
        for (QcmRecord record : inserted) {
            assertEquals(result.getBatchId(), record.getBatchId());
            assertEquals(result.getTriggerRequestId(), record.getTriggerRequestId());
            assertEquals(TriggerSource.REMOTE.getCode(), record.getTaskType());
            assertEquals("scada@10.0.0.1:5025", record.getTriggerUser());
            assertEquals("scada@10.0.0.1:5025", record.getUpdatedBy());
            assertEquals(QualityControlTypeEnum.MULTI_CHECK.getCode(), record.getQualityControlType());
            assertEquals("{\"source\":\"REMOTE_SDK\",\"operator\":{\"name\":\"scada\"}}",
                    record.getRecordSnapshot());
        }
        assertEquals(ParameterEnum.SO2.getCode(), inserted.get(0).getParameter());
        assertEquals(ParameterEnum.NO2.getCode(), inserted.get(1).getParameter());

        // 批次立即收敛 FAILED 终态（每行一次落库，逐字段错误进 result_evaluation）
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordService, times(2)).updateQcmRecord(captor.capture());
        for (QcmRecord record : captor.getAllValues()) {
            assertEquals(ExecutionStatusEnum.FAILED.getCode().intValue(), record.getExecutionStatus());
            assertEquals("INVALID_PARAM", record.getFailureReason());
            assertEquals("标气浓度必填", record.getResultEvaluation());
            assertEquals("标气浓度必填", record.getExecutionLog());
            assertNotNull(record.getEndTime());
        }
    }

    @Test
    void persistRejectedBatch_neverTouchesMutexGateOrComposer() {
        QcExecutionRequest req = singleInstrumentRequest().build();
        orchestrator.persistRejectedBatch(req, TriggerSource.REMOTE, "lims-system",
                "QUEUE_NOT_SUPPORTED", "SDK 触发不支持排队");

        verify(composer, never()).isRunning();
        verify(composer, never()).execute(any(ExecutorType.class), anyString(), any(Map.class));
        // 只写库不启动执行：executorMap 不挂 flow，计划推进也不参与（planId=null）
        assertTrue(entry.executorMap.isEmpty());
        verify(planMapper, never()).updateLastFireTime(any(), any(Instant.class), anyString());
        verify(snapshotWriter, never()).freezeResultSnapshot(org.mockito.ArgumentMatchers.anyLong(),
                anyString(), any(Map.class), any(List.class), any(List.class),
                isNull(), isNull(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), isNull());
    }

    @Test
    void persistRejectedBatch_blankIdentityArgsRejectedBeforeAnyWrite() {
        QcExecutionRequest req = singleInstrumentRequest().build();
        assertThrows(IllegalArgumentException.class, () -> orchestrator.persistRejectedBatch(
                null, TriggerSource.REMOTE, "lims-system", "INVALID_PARAM", "m"));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.persistRejectedBatch(
                req, TriggerSource.REMOTE, " ", "INVALID_PARAM", "m"));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.persistRejectedBatch(
                req, TriggerSource.REMOTE, "lims-system", " ", "m"));
        verify(recordService, never()).insertQcmRecordBatch(any(List.class));
        verify(recordService, never()).updateQcmRecord(any());
    }

    // ---------- 统一停止入口（§8 上移；REST/SDK 两面共用） ----------

    /** 中止入口轻量行（selectStopTarget* 三列：id/batch_id/execution_status）。 */
    private QcmRecord liteTarget(long id, String batchId, int executionStatus) {
        QcmRecord target = new QcmRecord();
        target.setId(id);
        target.setBatchId(batchId);
        target.setExecutionStatus(executionStatus);
        return target;
    }

    private void stubStopWrites(String batchId, int markedRows) {
        when(recordMapper.markStopInProgressClearEndTime(anyLong(), anyInt(), any(Instant.class), anyString()))
                .thenReturn(markedRows);
    }

    @Test
    void stopExecution_requiresExactlyOneAddressingKey() {
        StopOutcome empty = orchestrator.stopExecution(null, null, null, false, "tester");
        assertEquals(StopOutcome.Status.INVALID_PARAM, empty.getStatus());
        StopOutcome multi = orchestrator.stopExecution(11L, "batch-1", null, false, "tester");
        assertEquals(StopOutcome.Status.INVALID_PARAM, multi.getStatus());
        // 寻址不合法不触碰任何停止写路径
        verify(recordMapper, never()).markStopInProgressClearEndTime(
                anyLong(), anyInt(), any(Instant.class), anyString());
        verify(recordMapper, never()).terminateByBatchId(anyString(), anyInt(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void stopExecution_blankOperatorRejectedBeforeAnyLookup() {
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.stopExecution(11L, null, null, false, " "));
        verify(recordMapper, never()).selectStopTargetById(anyLong());
    }

    @Test
    void stopExecution_byRecordId_stopsFlowOnceMarksWholeBatchAndClearsMap() {
        when(recordMapper.selectStopTargetById(11L)).thenReturn(liteTarget(11L, "batch-1",
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        when(recordMapper.selectBatchRowIds("batch-1")).thenReturn(Arrays.asList(11L, 12L, 13L));
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        // 同批 N 行共享同一 flow，句柄可能只挂在其中一行上
        entry.executorMap.put(12L, flow);
        stubStopWrites("batch-1", 1);

        StopOutcome outcome = orchestrator.stopExecution(11L, null, null, false, "tester");

        assertEquals(StopOutcome.Status.INITIATED, outcome.getStatus());
        assertEquals("质控记录已中止", outcome.getMessage());
        assertEquals("batch-1", outcome.getBatchId());
        assertEquals(Arrays.asList(11L, 12L, 13L), outcome.getRecordIds());
        // flow 恰好停一次；批次全部行置 STOPPING；句柄不残留
        verify(flow, times(1)).stop();
        verify(recordMapper, times(3)).markStopInProgressClearEndTime(anyLong(),
                eq(ExecutionStatusEnum.STOPPING.getCode().intValue()), any(Instant.class), eq("tester"));
        assertTrue(entry.executorMap.isEmpty());
    }

    @Test
    void stopExecution_stopsFlowBeforeClearingExecutorMap_defect2() {
        when(recordMapper.selectStopTargetById(11L)).thenReturn(liteTarget(11L, "batch-1",
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        when(recordMapper.selectBatchRowIds("batch-1")).thenReturn(Collections.singletonList(11L));
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        entry.executorMap.put(11L, flow);
        stubStopWrites("batch-1", 1);
        // 缺陷#2 根治点：停止回调在 flow.stop() 内从 executorMap 取 flow 解析阶段时间线，
        // 断言 stop 被调用那一刻句柄仍在 map 上（先停后清）——回调拿得到 flow 才有时间线
        AtomicBoolean flowVisibleAtStop = new AtomicBoolean(false);
        doAnswer(inv -> {
            flowVisibleAtStop.set(entry.executorMap.containsKey(11L));
            return null;
        }).when(flow).stop();

        orchestrator.stopExecution(11L, null, null, false, "tester");

        assertTrue(flowVisibleAtStop.get(), "flow.stop() 时执行器句柄必须仍在 map（先停后清）");
        assertTrue(entry.executorMap.isEmpty());
    }

    @Test
    void stopExecution_resolvesBatchAndTriggerRequestHandles() {
        when(recordMapper.selectStopTargetByBatchId("batch-2")).thenReturn(liteTarget(21L, "batch-2",
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        when(recordMapper.selectStopTargetByTriggerRequestId("req-3")).thenReturn(liteTarget(31L, "batch-3",
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        when(recordMapper.selectBatchRowIds(anyString())).thenReturn(Collections.singletonList(21L));
        stubStopWrites("batch-2", 1);
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        entry.executorMap.put(21L, flow);

        assertEquals(StopOutcome.Status.INITIATED,
                orchestrator.stopExecution(null, "batch-2", null, false, "tester").getStatus());
        verify(recordMapper, never()).selectStopTargetById(anyLong());

        // 触发请求句柄寻址：同一受理的 N 行共享 trigger_request_id，任取一行定位批次
        when(recordMapper.selectBatchRowIds("batch-3")).thenReturn(Collections.singletonList(31L));
        entry.executorMap.put(31L, flow);
        StopOutcome byRequest = orchestrator.stopExecution(null, null, "req-3", false, "tester");
        assertEquals(StopOutcome.Status.INITIATED, byRequest.getStatus());
        assertEquals("batch-3", byRequest.getBatchId());
    }

    @Test
    void stopExecution_allRunningStopsCurrentRunningBatch_idleReportsNothingRunning() {
        // 单飞语义：executorMap 非空即当前唯一运行批次，任一行反查定位批次
        when(recordMapper.selectStopTargetById(anyLong())).thenReturn(liteTarget(11L, "batch-1",
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        when(recordMapper.selectBatchRowIds("batch-1")).thenReturn(Arrays.asList(11L, 12L));
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        entry.executorMap.put(12L, flow);
        stubStopWrites("batch-1", 2);

        StopOutcome allRunning = orchestrator.stopExecution(null, null, null, true, "tester");
        assertEquals(StopOutcome.Status.INITIATED, allRunning.getStatus());
        assertEquals("batch-1", allRunning.getBatchId());

        // 全停且无运行：非故障语义 NOTHING_RUNNING，不触碰任何写路径
        entry.executorMap.clear();
        StopOutcome idle = orchestrator.stopExecution(null, null, null, true, "tester");
        assertEquals(StopOutcome.Status.NOTHING_RUNNING, idle.getStatus());
        assertNull(idle.getBatchId());
        verify(recordMapper, never()).terminateByBatchId(anyString(), anyInt(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void stopExecution_unknownHandleReportsNotFound() {
        when(recordMapper.selectStopTargetById(99L)).thenReturn(null);
        when(recordMapper.selectStopTargetByBatchId("nope")).thenReturn(null);

        StopOutcome byRecord = orchestrator.stopExecution(99L, null, null, false, "tester");
        assertEquals(StopOutcome.Status.NOT_FOUND, byRecord.getStatus());
        assertEquals("质控记录不存在", byRecord.getMessage());
        StopOutcome byBatch = orchestrator.stopExecution(null, "nope", null, false, "tester");
        assertEquals(StopOutcome.Status.NOT_FOUND, byBatch.getStatus());
        assertEquals("批次不存在", byBatch.getMessage());
    }

    /**
     * 停止终态文案带操作者（缺陷 #3），且走 composer 的真实被停通道：
     * composer 把停止异常经 handle 转成 isException 结果对象完成 future（「流程被用户手动终止」原文案），
     * 编排器据此换算「流程被 {displayOperator} 手动终止」；执行失败同通道落库，不得被冒名改写。
     */
    @Test
    void stopExecution_stampsOperatorIntoStoppedTerminalViaComposerResultPath() {
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        stubIdleAndRunningFlow();
        when(composer.getRunningExecutor()).thenReturn(flow);
        CompletableFuture<ExecutorResultBase> future = new CompletableFuture<>();
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class))).thenReturn(future);
        BatchResult accepted = orchestrator.triggerExecution(
                singleInstrumentRequest().build(), TriggerSource.REMOTE, "sdk");
        List<QcmRecord> inserted = captureInsertedBatch(1);
        long recordId = accepted.getRecordIds().get(0);
        String batchId = inserted.get(0).getBatchId();
        when(recordMapper.selectStopTargetById(recordId)).thenReturn(liteTarget(recordId, batchId,
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        when(recordMapper.selectBatchRowIds(batchId)).thenReturn(Collections.singletonList(recordId));
        stubStopWrites(batchId, 1);
        // composer 真实行为：stop() 完成 future；此处让回调在 flow.stop() 内同步触发，
        // 同时验证暂存先于 stop 写入（回调读得到停止者）
        doAnswer(inv -> {
            ExecutorResultBase stopped = new ExecutorResultBase(false, true);
            stopped.setErrorMessage(QcmExecutionOrchestrator.COMPOSER_STOP_EVALUATION);
            stopped.setPhaseRecords(Collections.singletonList(
                    new PhaseExecutionRecord("p1", "阶段一", Instant.now(), Instant.now(), 10)));
            future.complete(stopped);
            return null;
        }).when(flow).stop();

        StopOutcome outcome = orchestrator.stopExecution(recordId, null, null, false, "scada@10.0.0.1:5025");

        assertEquals(StopOutcome.Status.INITIATED, outcome.getStatus());
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        // 第一次是启动前 RUNNING 标记，第二次是被停终态
        verify(recordService, times(2)).updateQcmRecord(captor.capture());
        QcmRecord terminal = captor.getAllValues().get(1);
        assertEquals(ExecutionStatusEnum.FAILED.getCode().intValue(), terminal.getExecutionStatus());
        assertEquals("流程被 scada@10.0.0.1:5025 手动终止", terminal.getResultEvaluation());
        // 行内留痕矩阵 §7：updated_by 同落 displayOperator——创建期旧 updatedBy 不得经回调回写覆盖
        assertEquals("scada@10.0.0.1:5025", terminal.getUpdatedBy());
        // 消费完即清：同一批次无第二次停止者可读
        verify(recordMapper, times(1)).markStopInProgressClearEndTime(
                eq(recordId), anyInt(), any(Instant.class), eq("scada@10.0.0.1:5025"));
    }

    /**
     * 快照冻结晚于通用 update：冻结若仍写系统账号（SNAPSHOT_ACTOR），停止者 updated_by 会在
     * updateQcmRecord 之后被覆写回去（浏览器回归 2026-09-09 实证：result_evaluation 正确带操作者
     * 而 updated_by=qcm-orchestrator）。行内留痕矩阵 §7：停止行终态归属=操作者，须贯通冻结层。
     */
    @Test
    void stopExecution_freezeLayerKeepsStopOperatorAsUpdatedBy() {
        ResultSnapshotWriter realWriter = new ResultSnapshotWriter(recordMapper,
                mock(QcmRecordPhaseMapper.class), mock(QcmRecordKeyParamMapper.class),
                mock(QcmRecordPointMapper.class), core);
        QcmExecutionOrchestrator wired = new QcmExecutionOrchestrator(
                recordService, planMapper, recordMapper, core, realWriter);
        wired.setFormatters(Collections.singletonList(formatter));

        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        stubIdleAndRunningFlow();
        when(composer.getRunningExecutor()).thenReturn(flow);
        CompletableFuture<ExecutorResultBase> future = new CompletableFuture<>();
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class))).thenReturn(future);
        BatchResult accepted = wired.triggerExecution(
                singleInstrumentRequest().build(), TriggerSource.REMOTE, "sdk");
        List<QcmRecord> inserted = captureInsertedBatch(1);
        long recordId = accepted.getRecordIds().get(0);
        String batchId = inserted.get(0).getBatchId();
        when(recordMapper.selectStopTargetById(recordId)).thenReturn(liteTarget(recordId, batchId,
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        when(recordMapper.selectBatchRowIds(batchId)).thenReturn(Collections.singletonList(recordId));
        stubStopWrites(batchId, 1);
        doAnswer(inv -> {
            ExecutorResultBase stopped = new ExecutorResultBase(false, true);
            stopped.setErrorMessage(QcmExecutionOrchestrator.COMPOSER_STOP_EVALUATION);
            future.complete(stopped);
            return null;
        }).when(flow).stop();

        wired.stopExecution(recordId, null, null, false, "scada@10.0.0.1:5025");

        ArgumentCaptor<QcmRecord> freezeCaptor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper, times(1)).updateResultSnapshot(freezeCaptor.capture());
        assertEquals("scada@10.0.0.1:5025", freezeCaptor.getValue().getUpdatedBy());
    }

    /** 同通道的普通执行失败（errorMessage 非 composer 停止文案）不得被冒名成「被停」。 */
    @Test
    void stopExecution_genuineFailureNotRenamedByStagedOperator() {
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        stubIdleAndRunningFlow();
        when(composer.getRunningExecutor()).thenReturn(flow);
        CompletableFuture<ExecutorResultBase> future = new CompletableFuture<>();
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class))).thenReturn(future);
        BatchResult accepted = orchestrator.triggerExecution(
                singleInstrumentRequest().build(), TriggerSource.REMOTE, "sdk");
        List<QcmRecord> inserted = captureInsertedBatch(1);
        long recordId = accepted.getRecordIds().get(0);
        String batchId = inserted.get(0).getBatchId();
        when(recordMapper.selectStopTargetById(recordId)).thenReturn(liteTarget(recordId, batchId,
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        when(recordMapper.selectBatchRowIds(batchId)).thenReturn(Collections.singletonList(recordId));
        stubStopWrites(batchId, 1);
        doAnswer(inv -> {
            ExecutorResultBase failure = new ExecutorResultBase(false, true);
            failure.setErrorMessage("zero check aborted by device");
            future.complete(failure);
            return null;
        }).when(flow).stop();

        orchestrator.stopExecution(recordId, null, null, false, "tester");

        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordService, times(2)).updateQcmRecord(captor.capture());
        assertEquals("zero check aborted by device", captor.getAllValues().get(1).getResultEvaluation());
    }

    /**
     * 异常通道（future 以 ExecutorStoppedException 完成）同样带停止者留痕，
     * 且阶段时间线取自仍在 map 上的 flow（缺陷 #2 的回调侧证据）。
     */
    @Test
    void stopExecution_stoppedExceptionCallbackKeepsPhaseTimelineAndOperator() {
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        when(flow.getExecutorPhases()).thenReturn(Collections.singletonList(
                new PhaseInfo("p1", "阶段一", 10)));
        stubIdleAndRunningFlow();
        when(composer.getRunningExecutor()).thenReturn(flow);
        CompletableFuture<ExecutorResultBase> future = new CompletableFuture<>();
        when(composer.execute(any(ExecutorType.class), anyString(), any(Map.class))).thenReturn(future);
        BatchResult accepted = orchestrator.triggerExecution(
                singleInstrumentRequest().build(), TriggerSource.REMOTE, "sdk");
        List<QcmRecord> inserted = captureInsertedBatch(1);
        long recordId = accepted.getRecordIds().get(0);
        String batchId = inserted.get(0).getBatchId();
        when(recordMapper.selectStopTargetById(recordId)).thenReturn(liteTarget(recordId, batchId,
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        when(recordMapper.selectBatchRowIds(batchId)).thenReturn(Collections.singletonList(recordId));
        stubStopWrites(batchId, 1);
        doAnswer(inv -> {
            future.completeExceptionally(new ExecutorStoppedException("ZeroCheckFlow", "SO2"));
            return null;
        }).when(flow).stop();

        orchestrator.stopExecution(recordId, null, null, false, "tester");

        ArgumentCaptor<ExecutorResultBase> stubCaptor = ArgumentCaptor.forClass(ExecutorResultBase.class);
        verify(formatter, times(1)).formatStub(any(), any(), stubCaptor.capture(), anyString(),
                org.mockito.ArgumentMatchers.anyLong());
        assertEquals("流程被 tester 手动终止", stubCaptor.getValue().getErrorMessage());
        assertEquals(1, stubCaptor.getValue().getPhaseRecords().size());
        ArgumentCaptor<QcmRecord> recordCaptor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordService, times(2)).updateQcmRecord(recordCaptor.capture());
        assertEquals("流程被 tester 手动终止", recordCaptor.getAllValues().get(1).getResultEvaluation());
        // 行内留痕矩阵 §7：异常通道同样 updated_by 落 displayOperator
        assertEquals("tester", recordCaptor.getAllValues().get(1).getUpdatedBy());
    }

    /** 未受理的停止（守卫 0 行=回调已先行收敛）不残留停止者暂存：后续终态文案不得再被改写。 */
    @Test
    void stopExecution_rejectedStopDoesNotLeaveStagedOperator() {
        when(recordMapper.selectStopTargetById(51L)).thenReturn(liteTarget(51L, "batch-51",
                ExecutionStatusEnum.FAILED.getCode().intValue()));
        when(recordMapper.selectBatchRowIds("batch-51")).thenReturn(Collections.singletonList(51L));
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        entry.executorMap.put(51L, flow);
        stubStopWrites("batch-51", 0);

        StopOutcome outcome = orchestrator.stopExecution(51L, null, null, false, "late-comer");

        assertEquals(StopOutcome.Status.ALREADY_SETTLED, outcome.getStatus());
        assertEquals("批次已结束，无需中止", outcome.getMessage());
        verify(flow, times(1)).stop();
        verify(recordMapper, never()).terminateByBatchId(anyString(), anyInt(), anyString(), any(Instant.class), anyString());
        verify(recordMapper, never()).terminateById(anyLong(), anyInt(), anyString(), any(Instant.class), anyString());
    }

    /** 无 batch_id 的历史行 + 运行 flow：停止照常受理，updated_by 仍留操作者（暂存槽位跳过，无 null key）。 */
    @Test
    void stopExecution_legacyRowWithoutBatchIdStillInitiates() {
        when(recordMapper.selectStopTargetById(71L)).thenReturn(liteTarget(71L, null,
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        AbstractCalibrationFlow flow = mock(AbstractCalibrationFlow.class);
        entry.executorMap.put(71L, flow);
        when(recordMapper.markStopInProgressClearEndTime(eq(71L), anyInt(), any(Instant.class), eq("tester")))
                .thenReturn(1);

        StopOutcome outcome = orchestrator.stopExecution(71L, null, null, false, "tester");

        assertEquals(StopOutcome.Status.INITIATED, outcome.getStatus());
        assertNull(outcome.getBatchId());
        verify(flow, times(1)).stop();
        verify(recordMapper).markStopInProgressClearEndTime(eq(71L),
                eq(ExecutionStatusEnum.STOPPING.getCode().intValue()), any(Instant.class), eq("tester"));
        verify(recordMapper, never()).terminateById(anyLong(), anyInt(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void runningBatchId_resolvesRunningBatchOrNullWhenIdle() {
        assertNull(orchestrator.runningBatchId());
        when(recordMapper.selectStopTargetById(anyLong())).thenReturn(liteTarget(11L, "batch-1",
                ExecutionStatusEnum.RUNNING.getCode().intValue()));
        entry.executorMap.put(11L, mock(AbstractCalibrationFlow.class));
        assertEquals("batch-1", orchestrator.runningBatchId());
    }

    /** 运行中批次行不存在属执行状态自洽被破坏：硬抛暴露，不以「无运行」掩盖。 */
    @Test
    void runningBatchId_missingRowForRunningFlowThrows() {
        when(recordMapper.selectStopTargetById(anyLong())).thenReturn(null);
        entry.executorMap.put(11L, mock(AbstractCalibrationFlow.class));
        assertThrows(IllegalStateException.class, () -> orchestrator.runningBatchId());
    }
}
