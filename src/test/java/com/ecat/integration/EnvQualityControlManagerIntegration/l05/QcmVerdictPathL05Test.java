package com.ecat.integration.EnvQualityControlManagerIntegration.l05;

import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EnvCalibrationComposerIntegration.CalibrationPolicy;
import com.ecat.integration.EnvCalibrationComposerIntegration.CheckResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.CheckStatus;
import com.ecat.integration.EnvCalibrationComposerIntegration.ComposerContext;
import com.ecat.integration.EnvCalibrationComposerIntegration.EnvCalibrationComposerIntegration;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorType;
import com.ecat.integration.EnvCalibrationComposerIntegration.MissingDevice;
import com.ecat.integration.EnvCalibrationComposerIntegration.ZeroCheckFlow;
import com.ecat.integration.EnvQualityControlManagerIntegration.EnvQualityControlManagerIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPhase;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordKeyParamMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPhaseMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPointMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.ResultSnapshotWriter;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlTask;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicState.ILogicAttribute;
import com.ecat.integration.logicdevice.Meta.AttrIdDevice.GasMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L0.5 伪集成层（M7.1，qcm 域首落地）：判定算法验证秒级化（G4 / round4 走查痛点 8）。
 *
 * <p>判定算法外部依赖面笔记（读 src/main 梳理，2026-10-24）：</p>
 * <ul>
 *   <li>判定真身=composer 仓 {@code ZeroCheckFlow}：drift=采样均值 →
 *       {@code CalibrationPolicy.classify(absDrift, passLimit, calibLimit)} 分档
 *       （STANDARD 三档 PASS/NEED_CALIBRATION/EXCEED_LIMIT；CALIBRATE_LOW_DRIFT 两档
 *       合格区内也校）→ CheckResult(status, deviceValue)；NaN 前置拦截保底 NO_DATA。</li>
 *   <li>相位时序=PhaseManager：prepare/setup/check/calibration/recovery 五相位，
 *       nextPhase 记 Instant 时间戳，预估秒数=ctx 参数算术（cmdDelay×n+5 等）。</li>
 *   <li>composer 交互面=isRunning/execute/getRunningExecutor（bridge 经 Object 强转，
 *       设备读写全走 LogicDevice attr）。</li>
 *   <li>qcm 侧=orchestrator 三源触发 → bridge 回调四分支 → 真 EnvQualityControlTask
 *       formatter 出 execution_log JSON → freezeSnapshotFromLog → ResultSnapshotWriter
 *       台账+三子表冻结。</li>
 * </ul>
 *
 * <p>L0.5 分界：设备面（LogicDevice/ILogicAttribute）与时长参数 mock/归零，
 * flow 相位推进、漂移分档、自动校准、恢复链、回调落库、execution_log 组装、
 * 快照冻结全部走生产代码——原「只有 e2e 能验」的判定路径下沉为秒级单测。</p>
 *
 * @author coffee
 */
class QcmVerdictPathL05Test {

    private static final String GAS = "so2";

    private IQcmRecordService recordService;
    private QcmPlanMapper planMapper;
    private QcmRecordMapper recordMapper;
    private QcmRecordPhaseMapper phaseMapper;
    private QcmRecordKeyParamMapper keyParamMapper;
    private QcmRecordPointMapper pointMapper;
    private EcatCore core;
    private IntegrationRegistry registry;
    private EnvCalibrationComposerIntegration composer;
    private EnvQualityControlManagerIntegration entry;
    private QcmExecutionOrchestrator orchestrator;

    private LogicDevice calibrator;
    private LogicDevice analyzer;
    private ILogicAttribute<?> dispatchCommandAttr;

    /** execute 时启动的真 flow（isRunning/getRunningExecutor 语义源）。 */
    private final AtomicReference<TestableZeroCheckFlow> launchedFlow = new AtomicReference<>();
    /** 最近一次 flow.execute() 返回的结果 future（确定性 await 用）。 */
    private final AtomicReference<CompletableFuture<ExecutorResultBase>> lastFutureRef = new AtomicReference<>();
    /** 每个 flow 使用的 ctx（策略/时长参数按用例装配）。 */
    private final AtomicReference<ComposerContextRef> ctxRef = new AtomicReference<>();

    /**
     * 真 ZeroCheckFlow 的测试面：仅注入分析仪读值序列（模拟采样）。
     * readValues=null 时走真实读路径——设备 attr 缺失即命中生产「属性缺失」异常点（断连用例）。
     * delayAsync 不打桩：ctx 时长参数归零后 seconds<=0 自然直通；并发用例给 stableTime=1
     * 走真实调度等待，作为「B 计划触发时 A 相位仍在推进」的确定性时间墙。
     */
    static class TestableZeroCheckFlow extends ZeroCheckFlow {
        private Iterator<Float> readValues;

        TestableZeroCheckFlow(ComposerContext ctx, String gas) {
            super(ctx, gas);
        }

        void injectReadValues(List<Float> values) {
            this.readValues = values.iterator();
        }

        @Override
        protected float readAnalyzerValue(String attrId) {
            if (readValues == null) {
                return super.readAnalyzerValue(attrId);
            }
            return readValues.next();
        }
    }

    /** ctx 装配参数（零延迟缺省，策略/稳定等待/读值序列按用例覆写；readValues=null=走真实读路径）。 */
    private static final class ComposerContextRef {
        final CalibrationPolicy policy;
        final int stableTimeSeconds;
        final List<Float> readValues;

        ComposerContextRef(CalibrationPolicy policy, int stableTimeSeconds, List<Float> readValues) {
            this.policy = policy;
            this.stableTimeSeconds = stableTimeSeconds;
            this.readValues = readValues;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        recordService = mock(IQcmRecordService.class);
        planMapper = mock(QcmPlanMapper.class);
        recordMapper = mock(QcmRecordMapper.class);
        phaseMapper = mock(QcmRecordPhaseMapper.class);
        keyParamMapper = mock(QcmRecordKeyParamMapper.class);
        pointMapper = mock(QcmRecordPointMapper.class);
        core = mock(EcatCore.class);
        registry = mock(IntegrationRegistry.class);
        composer = mock(EnvCalibrationComposerIntegration.class);
        entry = new EnvQualityControlManagerIntegration();
        when(core.getIntegrationRegistry()).thenReturn(registry);
        when(registry.getIntegration("integration-env-calibration-composer")).thenReturn(composer);
        when(registry.getIntegration("integration-env-qc-manager")).thenReturn(entry);
        // 模拟 insertBatch 的 useGeneratedKeys id 回填（生产由 DB 驱动回填）
        when(recordService.insertQcmRecordBatch(anyList())).thenAnswer(inv -> {
            List<QcmRecord> inserted = inv.getArgument(0);
            for (int i = 0; i < inserted.size(); i++) {
                inserted.get(i).setId(100L + i);
            }
            return inserted.size();
        });

        calibrator = mockDevice("calibrator-so2", new HashMap<>());
        Map<String, ILogicAttribute<?>> analyzerNamed = new HashMap<>();
        dispatchCommandAttr = commandAttr();
        analyzerNamed.put(GasMonitor.DISPATCH_COMMAND, dispatchCommandAttr);
        analyzerNamed.put(GasMonitor.MANUAL_STATUS, commandAttr());
        analyzer = mockDevice("analyzer-so2", analyzerNamed);

        // 真 formatter + 真 snapshotWriter：execution_log JSON 契约与三子表冻结全走生产代码
        ResultSnapshotWriter snapshotWriter = new ResultSnapshotWriter(
                recordMapper, phaseMapper, keyParamMapper, pointMapper, core);
        orchestrator = new QcmExecutionOrchestrator(recordService, planMapper, recordMapper, core, snapshotWriter);
        orchestrator.setFormatters(Collections.singletonList(new EnvQualityControlTask()));

        // 伪 composer：execute 启动真 flow（相位推进/判定/恢复全真），isRunning/句柄由真 flow 状态驱动
        when(composer.isRunning()).thenAnswer(inv -> {
            TestableZeroCheckFlow flow = launchedFlow.get();
            return flow != null && flow.isRunning();
        });
        when(composer.getRunningExecutor()).thenAnswer(inv -> launchedFlow.get());
        when(composer.execute(any(ExecutorType.class), anyString(), anyMap())).thenAnswer(inv -> {
            ComposerContextRef ref = ctxRef.get();
            TestableZeroCheckFlow flow = new TestableZeroCheckFlow(buildCtx(ref.policy, ref.stableTimeSeconds), GAS);
            if (ref.readValues != null) {
                flow.injectReadValues(ref.readValues);
            }
            launchedFlow.set(flow);
            CompletableFuture<ExecutorResultBase> future = flow.execute();
            lastFutureRef.set(future);
            return future;
        });
    }

    // ---------- ① 正常判定终态：STANDARD + 漂移 5ppb（≤passLimit 10）→ PASS → SUCCESS ----------

    @Test
    void normalVerdict_passTerminal_withRealPhaseTimelineFrozen() throws Exception {
        ctxRef.set(new ComposerContextRef(CalibrationPolicy.STANDARD, 0, Arrays.asList(5f, 5f, 5f)));

        BatchResult accepted = triggerZeroCheck();
        assertEquals(BatchResult.Status.ACCEPTED, accepted.getStatus());

        ExecutorResultBase flowResult = lastFuture().get(10, TimeUnit.SECONDS);
        assertTrue(((CheckResult) flowResult).isPass(), "漂移 5ppb ≤ passLimit 10 应判 PASS");
        assertEquals(CheckStatus.PASS, ((CheckResult) flowResult).getStatus());
        assertEquals(5.0f, ((CheckResult) flowResult).getDeviceValue(), 1e-6);

        // 台账终态：标准质控完成即 SUCCESS（未通过也 SUCCESS 的同族语义）
        QcmRecord terminal = singleTerminalRecord(2);
        assertEquals(ExecutionStatusEnum.SUCCESS.getCode().intValue(), terminal.getExecutionStatus());
        assertTrue(terminal.getResultEvaluation().startsWith("校准任务完成，且已通过"),
                terminal.getResultEvaluation());

        // execution_log 真契约：result 判定标量（设备值/限值/isPass）落 JSON
        Map<String, Object> logRoot = parseExecutionLog(terminal.getExecutionLog());
        Map<String, Object> judgement = judgementOf(logRoot);
        assertEquals(true, judgement.get("isPass"));
        assertEquals(5.0, ((Number) judgement.get("deviceValue")).doubleValue(), 1e-6);
        assertEquals(10.0, ((Number) judgement.get("checkPassLimit")).doubleValue(), 1e-6);
        assertEquals(0.0, ((Number) judgement.get("stdValue")).doubleValue(), 1e-6);

        // 相位时间线冻结：5 相位行 + 预算算术锁（cmdDelay=0/valveCount=0 形态）
        List<QcmRecordPhaseRow> phases = capturedPhaseRows(1);
        Map<String, QcmRecordPhaseRow> byCode = phasesByCode(phases);
        assertEquals(Arrays.asList("prepare", "setup", "check", "calibration", "recovery"),
                phases.stream().map(p -> p.phaseCode).collect(Collectors.toList()));
        assertBudget(byCode.get("prepare"), 5);
        assertBudget(byCode.get("setup"), 7);
        assertBudget(byCode.get("check"), 30);
        assertBudget(byCode.get("calibration"), 5);
        // PASS 直通：calibration 相位未进入（无时间戳）；其余相位有时间戳（相位时序真实推进）
        assertNull(byCode.get("calibration").startTime, "未触发自动校准时 calibration 相位不应有时间戳");
        for (String started : new String[]{"prepare", "setup", "check", "recovery"}) {
            assertNotNull(byCode.get(started).startTime, started + " 应有开始时间");
            assertNotNull(byCode.get(started).endTime, started + " 应有结束时间");
        }

        // composer 交互证据：检查相位收过 ZERO_START（prepare 的采样态复位也会发 END 类命令，序列含历史），
        // 且整个批次以恢复链 ZERO_END→SPAN_END 收尾
        List<String> dispatch = capturedWrites(dispatchCommandAttr);
        assertTrue(dispatch.contains(GasMonitor.DispatchCommandCommands.ZERO_START),
                "零点检查应下发 ZERO_START，实际序列: " + dispatch);
        assertEquals(GasMonitor.DispatchCommandCommands.ZERO_END, dispatch.get(dispatch.size() - 2));
        assertEquals(GasMonitor.DispatchCommandCommands.SPAN_END, dispatch.get(dispatch.size() - 1));

        // 台账行结果快照冻结（updateResultSnapshot）+ 执行器句柄清账
        verify(recordMapper).updateResultSnapshot(any(QcmRecord.class));
        assertTrue(entry.executorMap.isEmpty());
    }

    // ---------- ② 漂移档位边界：STANDARD + 漂移 15ppb（pass 10 < x ≤ calib 25）→ 自动校准后 PASS ----------

    @Test
    void driftBoundary_standardBetweenLimits_triggersAutoCalibrationThenPass() throws Exception {
        ctxRef.set(new ComposerContextRef(CalibrationPolicy.STANDARD, 0,
                Arrays.asList(15f, 15f, 15f, 1f, 1f, 1f)));

        triggerZeroCheck();
        ExecutorResultBase flowResult = lastFuture().get(10, TimeUnit.SECONDS);
        CheckResult check = (CheckResult) flowResult;
        assertEquals(CheckStatus.PASS, check.getStatus(), "漂移落在 (pass, calib] 应自动校准后 PASS");
        assertNotNull(check.getVerificationValue(), "自动校准后应有校准后验证值");
        assertEquals(1.0f, check.getVerificationValue(), 1e-6);

        QcmRecord terminal = singleTerminalRecord(2);
        assertEquals(ExecutionStatusEnum.SUCCESS.getCode().intValue(), terminal.getExecutionStatus());
        assertTrue(terminal.getResultEvaluation().startsWith("校准任务完成，且已通过"),
                terminal.getResultEvaluation());

        // 判定标量：deviceValue 保留原始漂移 15，verificationValue 为校后验证均值
        Map<String, Object> judgement = judgementOf(parseExecutionLog(terminal.getExecutionLog()));
        assertEquals(15.0, ((Number) judgement.get("deviceValue")).doubleValue(), 1e-6);
        assertEquals(1.0, ((Number) judgement.get("verificationValue")).doubleValue(), 1e-6);

        // 相位证据：calibration 相位真实进入（有时间戳）；交互证据：ZERO_CALIBRATION 已下发
        Map<String, QcmRecordPhaseRow> byCode = phasesByCode(capturedPhaseRows(1));
        assertNotNull(byCode.get("calibration").startTime, "自动校准应进入 calibration 相位");
        assertNotNull(byCode.get("calibration").endTime);
        List<String> dispatch = capturedWrites(dispatchCommandAttr);
        assertTrue(dispatch.contains(GasMonitor.DispatchCommandCommands.ZERO_CALIBRATION),
                "自动校准应下发 ZERO_CALIBRATION，实际序列: " + dispatch);
    }

    // ---------- ③ 漂移档位边界：CALIBRATE_LOW_DRIFT + 漂移 5ppb（STANDARD 下的合格区）→ 也触发自动校准 ----------

    @Test
    void driftBoundary_lowDriftPolicy_calibratesInsideStandardPassZone() throws Exception {
        ctxRef.set(new ComposerContextRef(CalibrationPolicy.CALIBRATE_LOW_DRIFT, 0,
                Arrays.asList(5f, 5f, 5f, 2f, 2f, 2f)));

        triggerZeroCheck();
        ExecutorResultBase flowResult = lastFuture().get(10, TimeUnit.SECONDS);
        CheckResult check = (CheckResult) flowResult;
        assertEquals(CheckStatus.PASS, check.getStatus(),
                "低偏差策略下合格区内漂移也自动校准，校后 PASS");
        assertTrue(String.valueOf(check.getResultMessage()).contains("低偏差校准已启用"),
                "策略留痕后缀应进结果消息: " + check.getResultMessage());

        QcmRecord terminal = singleTerminalRecord(2);
        assertEquals(ExecutionStatusEnum.SUCCESS.getCode().intValue(), terminal.getExecutionStatus());
        assertTrue(terminal.getResultEvaluation().contains("低偏差校准已启用"),
                "策略后缀应穿透到台账评定: " + terminal.getResultEvaluation());

        // 与 ① 同漂移 5ppb、不同策略 → 校准路径分叉：calibration 相位进入 + ZERO_CALIBRATION 下发
        Map<String, QcmRecordPhaseRow> byCode = phasesByCode(capturedPhaseRows(1));
        assertNotNull(byCode.get("calibration").startTime,
                "CALIBRATE_LOW_DRIFT 下 5ppb 也应进入校准相位（与 STANDARD 的 PASS 直通分叉）");
        List<String> dispatch = capturedWrites(dispatchCommandAttr);
        assertTrue(dispatch.contains(GasMonitor.DispatchCommandCommands.ZERO_CALIBRATION),
                "实际序列: " + dispatch);
    }

    // ---------- ④ 断连恢复相位：分析仪读数故障 → FAILED 终态 + 相位时间线留痕 + 恢复链收尾 ----------

    @Test
    void analyzerReadFailure_failedTerminal_withPhaseTimelineAndRecovery() throws Exception {
        // 不注入读值序列：真实读路径命中 mock 设备「属性映射非 AttributeBase」= 离线读故障
        ctxRef.set(new ComposerContextRef(CalibrationPolicy.STANDARD, 0, null));

        triggerZeroCheck();
        ExecutorResultBase flowResult = lastFuture().get(10, TimeUnit.SECONDS);
        assertTrue(flowResult.isException(), "读故障应转异常结果");
        assertTrue(flowResult.getErrorMessage().contains("无 so2 属性"),
                "应保留生产读故障定位信息: " + flowResult.getErrorMessage());

        QcmRecord terminal = singleTerminalRecord(2);
        assertEquals(ExecutionStatusEnum.FAILED.getCode().intValue(), terminal.getExecutionStatus());
        // isException 结果通道的评定=errorMessage 原文（「校准任务过程异常」前缀属 exceptionally 通道，此处不适用）
        assertTrue(terminal.getResultEvaluation().contains("分析仪 analyzer-so2"),
                terminal.getResultEvaluation());
        assertTrue(terminal.getResultEvaluation().contains("无 so2 属性"),
                "失败原因应可定位到设备读故障: " + terminal.getResultEvaluation());

        // 相位时间线留痕：已推进的相位带时间戳，校准未进入，恢复相位已执行（断连后设备收尾）
        Map<String, QcmRecordPhaseRow> byCode = phasesByCode(capturedPhaseRows(1));
        for (String started : new String[]{"prepare", "setup", "check", "recovery"}) {
            assertNotNull(byCode.get(started).startTime, started + " 失败路径也应留相位时间戳");
            assertNotNull(byCode.get(started).endTime);
        }
        assertNull(byCode.get("calibration").startTime);
        List<String> dispatch = capturedWrites(dispatchCommandAttr);
        assertEquals(GasMonitor.DispatchCommandCommands.ZERO_END, dispatch.get(dispatch.size() - 2),
                "恢复链应以 ZERO_END→SPAN_END 收尾");
        assertEquals(GasMonitor.DispatchCommandCommands.SPAN_END, dispatch.get(dispatch.size() - 1));

        // 执行器句柄清账（停止寻址面不残留）
        assertTrue(entry.executorMap.isEmpty());
    }

    // ---------- ⑤ 并发计划相位交错：A 执行中 B 触发 → BUSY_CONFLICT，A 相位照常走完 ----------

    @Test
    void concurrentPlans_secondRejectedWhileFirstPhaseTimelineRuns() throws Exception {
        // 稳定等待=1s：真实调度等待作时间墙——B 在 A 相位推进窗口内触发，无 sleep
        ctxRef.set(new ComposerContextRef(CalibrationPolicy.STANDARD, 1, Arrays.asList(5f, 5f, 5f)));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<BatchResult> acceptedF = pool.submit(() -> triggerZeroCheck());
            BatchResult acceptedA = acceptedF.get(5, TimeUnit.SECONDS);
            assertEquals(BatchResult.Status.ACCEPTED, acceptedA.getStatus());

            // A 相位仍在推进（future 未完成），B 立即触发必撞互斥闸
            BatchResult rejectedB = orchestrator.triggerExecution(zeroCheckRequest(),
                    TriggerSource.SCHEDULED, "system-plan-b");
            assertEquals(BatchResult.Status.REJECTED_BUSY_CONFLICT, rejectedB.getStatus());
            assertEquals("EXECUTOR_BUSY_CONFLICT", rejectedB.getFailureReason());

            // B 的留痕行：FAILED + 结构化原因；A 的受理行此时仍 RUNNING（相位交错证据）
            ArgumentCaptor<List<QcmRecord>> insertCaptor = insertCaptor();
            verify(recordService, times(2)).insertQcmRecordBatch(insertCaptor.capture());
            List<QcmRecord> batchB = insertCaptor.getAllValues().get(1);
            assertEquals(1, batchB.size());
            assertEquals(ExecutionStatusEnum.FAILED.getCode().intValue(),
                    batchB.get(0).getExecutionStatus());
            assertEquals("EXECUTOR_BUSY_CONFLICT", batchB.get(0).getFailureReason());

            // A 走完全部相位正常收敛：SUCCESS + 相位时间线完整 + 句柄清账
            lastFuture().get(15, TimeUnit.SECONDS);
            ArgumentCaptor<QcmRecord> updateCaptor = ArgumentCaptor.forClass(QcmRecord.class);
            verify(recordService, times(3)).updateQcmRecord(updateCaptor.capture());
            QcmRecord terminalA = updateCaptor.getAllValues().stream()
                    .filter(r -> r.getBatchId().equals(acceptedA.getBatchId()))
                    .reduce((a, b) -> b).get();
            assertEquals(ExecutionStatusEnum.SUCCESS.getCode().intValue(), terminalA.getExecutionStatus());
            assertTrue(terminalA.getResultEvaluation().startsWith("校准任务完成，且已通过"),
                    terminalA.getResultEvaluation());
            assertTrue(entry.executorMap.isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 装配与断言辅助 ====================

    private BatchResult triggerZeroCheck() {
        return orchestrator.triggerExecution(zeroCheckRequest(), TriggerSource.MANUAL, "l05-user");
    }

    private static QcExecutionRequest zeroCheckRequest() {
        return QcExecutionRequest.builder()
                .qcType("zero_check")
                .instruments(Collections.singletonList("SO2"))
                .build();
    }

    private CompletableFuture<ExecutorResultBase> lastFuture() {
        return lastFutureRef.get();
    }

    /** 终态行捕获：第 1 次 update 是受理 RUNNING 标记，末次是终态（单行批次）。 */
    private QcmRecord singleTerminalRecord(int expectedUpdates) {
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordService, times(expectedUpdates)).updateQcmRecord(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<QcmRecord>> insertCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    /** 冻结进 qcm_record_phase 子表的相位行（insertBatch 单批 5 相位）。 */
    @SuppressWarnings("unchecked")
    private List<QcmRecordPhaseRow> capturedPhaseRows(int expectedBatches) {
        ArgumentCaptor<List<QcmRecordPhase>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(phaseMapper, times(expectedBatches)).insertBatch(captor.capture());
        List<QcmRecordPhaseRow> rows = new ArrayList<>();
        for (QcmRecordPhase row : captor.getValue()) {
            QcmRecordPhaseRow r = new QcmRecordPhaseRow();
            r.phaseCode = row.getPhaseCode();
            r.startTime = row.getStartTime();
            r.endTime = row.getEndTime();
            r.estimatedSeconds = row.getEstimatedSeconds();
            rows.add(r);
        }
        return rows;
    }

    private static final class QcmRecordPhaseRow {
        String phaseCode;
        Instant startTime;
        Instant endTime;
        Integer estimatedSeconds;
    }

    private static Map<String, QcmRecordPhaseRow> phasesByCode(List<QcmRecordPhaseRow> phases) {
        Map<String, QcmRecordPhaseRow> byCode = new HashMap<>();
        for (QcmRecordPhaseRow row : phases) {
            byCode.put(row.phaseCode, row);
        }
        return byCode;
    }

    /** 相位预算锁：estimatedSeconds=ctx 零延迟形态下的算术结果（definePhases 契约）。 */
    private static void assertBudget(QcmRecordPhaseRow row, int expected) {
        assertEquals(expected, row.estimatedSeconds, "相位 " + row.phaseCode + " 预算算术");
    }

    /** mock 设备 attr 的累计写入序列（composer 交互证据）。 */
    @SuppressWarnings("unchecked")
    private static List<String> capturedWrites(ILogicAttribute<?> attr) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify((ILogicAttribute<String>) attr, org.mockito.Mockito.atLeastOnce())
                .setDisplayValue(captor.capture());
        return captor.getAllValues();
    }

    /** execution_log JSON 根对象（真 formatter 产物解析）。 */
    private static Map<String, Object> parseExecutionLog(String json) {
        return com.alibaba.fastjson2.JSON.parseObject(json, new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> judgementOf(Map<String, Object> logRoot) {
        Object result = logRoot.get("result");
        assertNotNull(result, "execution_log 应含 result 判定标量体");
        return (Map<String, Object>) result;
    }

    /** 命令属性 mock：setDisplayValue 恒成功（并留累计写入供断言）。 */
    private static ILogicAttribute<?> commandAttr() {
        ILogicAttribute<?> attr = mock(ILogicAttribute.class);
        when(attr.setDisplayValue(anyString())).thenReturn(CompletableFuture.completedFuture(true));
        return attr;
    }

    /** mock LogicDevice：attrMap 对任意 key 都给出命令属性（缺配置=全配形态）。 */
    private static LogicDevice mockDevice(String deviceId, Map<String, ILogicAttribute<?>> namedAttrs) {
        LogicDevice device = mock(LogicDevice.class);
        when(device.getId()).thenReturn(deviceId);
        // Mockito 无法表达 Class<Map<String,ILogicAttribute<?>>，raw mock 是泛型边界的既定用法
        @SuppressWarnings("unchecked")
        Map<String, ILogicAttribute<?>> attrMap = mock(Map.class);
        when(attrMap.get(anyString())).thenAnswer(inv ->
                namedAttrs.computeIfAbsent((String) inv.getArgument(0), k -> commandAttr()));
        when(device.getAttrMap()).thenReturn(attrMap);
        return device;
    }

    /**
     * 反射构建 ComposerContext（镜像 composer 仓 TestableFlowContext 模式，跳过 Builder 的
     * EcatCore 依赖）：时长参数归零（delayAsync seconds<=0 直通=秒级执行），策略/稳定等待按用例注入。
     * Objenesis 跳过全部构造器（与序列化式构造等价），字段初始化器不执行，下方逐字段回填补缺。
     */
    private ComposerContext buildCtx(CalibrationPolicy policy, int stableTimeSeconds) {
        ComposerContext ctx = new ObjenesisStd().newInstance(ComposerContext.class);

        setField(ctx, "calibrator", calibrator);
        setField(ctx, "targetAnalyzer", analyzer);
        setField(ctx, "zeroGasRelay", null);
        setField(ctx, "valveMap", new HashMap<>());
        setField(ctx, "targetGas", GAS);
        setField(ctx, "missingDevices", EnumSet.noneOf(MissingDevice.class));
        setField(ctx, "stableTimeSeconds", stableTimeSeconds);
        setField(ctx, "sampleCount", 3);
        setField(ctx, "sampleIntervalSeconds", 0);
        setField(ctx, "spanConcentrationPpb", 400.0f);
        setField(ctx, "rangePpb", 500.0f);
        setField(ctx, "spanCheckPassLimitPercent", 5.0f);
        setField(ctx, "spanCheckCalibLimitPercent", 10.0f);
        setField(ctx, "correlationThreshold", 0.999f);
        setField(ctx, "precisionRsdLimit", 5.0f);
        setField(ctx, "precisionRounds", 2);
        setField(ctx, "precisionSpanRatio", 0.2f);
        setField(ctx, "precisionZeroLimitPpb", 10.0f);
        setField(ctx, "conversionEfficiencyLimit", 96.0f);
        setField(ctx, "conversionO3Ratio", 0.5f);
        setField(ctx, "conversionRounds", 3);
        setField(ctx, "gptTimeSeconds", 0);
        setField(ctx, "multiPointPercents", new float[]{0.0f, 0.2f, 0.4f, 0.6f, 0.8f});
        setField(ctx, "accuracyPointPercents", new float[]{0.2f, 0.4f, 0.6f, 0.8f});
        setField(ctx, "commandDelaySeconds", 0);
        setField(ctx, "zeroGasOpenDelaySeconds", 0);
        setField(ctx, "recoveryDelaySeconds", 0);
        setField(ctx, "calibrationPolicy", policy);
        setField(ctx, "zeroCheckPassLimitPpb", 10.0f);
        setField(ctx, "zeroCheckCalibLimitPpb", 25.0f);
        setField(ctx, "calibrationTimeSeconds", 0);
        setField(ctx, "flowRateLpm", 4.0f);
        setField(ctx, "verificationStableTimeSeconds", 0);
        setField(ctx, "verificationSampleCount", 3);
        setField(ctx, "staleDataThresholdSeconds", 120);
        return ctx;
    }

    /** 反射设置 ComposerContext 私有字段（无 setter；镜像 composer 仓测试装配模式）。 */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set ctx field: " + fieldName, e);
        }
    }
}
