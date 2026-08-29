package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.EcatCore;
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
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.FlowDefaults;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 质控执行统一触发编排器（FR-02-01，三源收敛点）：SCHEDULED(调度器)/MANUAL(页面)/REMOTE(SDK)
 * 殊途同归进 {@link #triggerExecution}。原 EnvQualityControlTask / EnvQualityControlCustomTask
 * executeImpl 的执行编排（记录创建/互斥闸/flowParams 组装/回调落库）平移至此，语义不变；
 * 两 Task 薄化为参数适配层（G-STRUCT-1，合并约 70% 重复编排代码）。
 *
 * <p>与旧 Task 的三处刻意差异：</p>
 * <ul>
 *   <li>N 仪器一批 N 行同 batch_id 单语句写入（G-BUG-10 创建原子性）；</li>
 *   <li>互斥闸忙时不再 throw——N 条记录落 FAILED + failure_reason=EXECUTOR_BUSY_CONFLICT 终态留痕，
 *       返回 REJECTED_BUSY_CONFLICT（D10 三类来源都写记录）；</li>
 *   <li>future 异常回调不再向 future 重抛（G-BUG-4）——一律落库终态 FAILED + execution_log 留因。</li>
 * </ul>
 */
@Service
public class QcmExecutionOrchestrator {

    static final String BUSY_CONFLICT_REASON = "EXECUTOR_BUSY_CONFLICT";
    static final String BUSY_CONFLICT_MESSAGE = "校准任务退出 已有执行中的校准任务";
    /** multi_zero_check 等 composer 未接线类型的结构化失败原因（FR-02-14 中间态，与 BUSY 留痕同构）。 */
    static final String NOT_READY_REASON = "EXECUTOR_TYPE_NOT_READY";
    static final String NOT_READY_MESSAGE = "多仪器零点 flow 未接线";
    static final String RUNNING_MESSAGE = "校准任务执行中...";
    static final String NO_EXECUTOR_MESSAGE = "校准任务未执行 没有可用的执行器";
    /** 编排器维护计划触发时刻的更新人（系统操作，非 ruoyi 用户）。 */
    static final String ORCHESTRATOR_ACTOR = "qcm-orchestrator";

    private static final String COMPOSER_INTEGRATION_ID = "integration-env-calibration-composer";
    private static final String SELF_INTEGRATION_ID = "integration-env-qc-manager";

    private static final Logger log = LoggerFactory.getLogger(QcmExecutionOrchestrator.class);

    private final IQcmRecordService recordService;
    private final QcmPlanMapper planMapper;
    private final EcatCore core;
    private final ResultSnapshotWriter snapshotWriter;

    /**
     * 进程内互斥闸锁：composer 无内建互斥（execute 不拒绝并发，直接覆盖 runningFlow 成不可 stop 的孤儿 flow），
     * 此锁是唯一防线；SCHEDULED/MANUAL/REMOTE 三源都经 triggerExecution 收敛于此。
     * 临界段 = 「互斥检查→创建记录→execute 异步启动」（不含结果回调——回调在锁外由 future 线程驱动）。
     */
    private final Object triggerMutex = new Object();

    /** 结果格式化器（标准质控/人工核查两套 constructResult），集成入口 onStart 注册 */
    private volatile List<QcResultFormatter> formatters = Collections.emptyList();

    @Autowired
    public QcmExecutionOrchestrator(IQcmRecordService recordService, QcmPlanMapper planMapper, EcatCore core,
                                     ResultSnapshotWriter snapshotWriter) {
        this.recordService = recordService;
        this.planMapper = planMapper;
        this.core = core;
        this.snapshotWriter = snapshotWriter;
    }

    /** 集成入口 onStart 注册两 Task 的结果格式化实现（构造在 Spring，Task 实例在 TaskExecutor 侧）。 */
    public void setFormatters(List<QcResultFormatter> formatters) {
        this.formatters = formatters;
    }

    /**
     * 三源统一触发入口（FR-02-01）。
     *
     * @param req          执行请求（参数已由调用方校验，此处防御性复校非空）
     * @param source       触发源（决定 qcm_record.task_type 落 TaskTypeEnum code）
     * @param triggerUser  触发者（MANUAL=用户名 / SCHEDULED=system / REMOTE=来源名）
     * @return ACCEPTED（含 batchId+recordIds，执行结果异步落库）/ REJECTED_BUSY_CONFLICT（执行器被占，
     *         N 条记录已写 FAILED+EXECUTOR_BUSY_CONFLICT 终态留痕）/ REJECTED_EXECUTOR_TYPE_NOT_READY
     *         （multi_zero_check 等 composer 未接线类型，闸前拒绝，N 条记录已写
     *         FAILED+EXECUTOR_TYPE_NOT_READY 终态留痕，FR-02-14 中间态）
     */
    public BatchResult triggerExecution(QcExecutionRequest req, TriggerSource source, String triggerUser) {
        // 1. 防御性复校（2.4 Validator / ScheduleSpecs 为第一道）
        if (req == null || req.getQcType() == null || req.getQcType().trim().isEmpty()) {
            throw new IllegalArgumentException("qcType is required to trigger quality control execution");
        }
        if (req.getInstruments() == null || req.getInstruments().isEmpty()) {
            throw new IllegalArgumentException("instruments is required to trigger quality control execution");
        }
        if (triggerUser == null || triggerUser.trim().isEmpty()) {
            throw new IllegalArgumentException("triggerUser is required to trigger quality control execution");
        }

        QualityControlTypeEnum qcEnum = QualityControlTypeEnum.valueOf(req.getQcType().trim().toUpperCase());
        QcResultFormatter formatter = formatterFor(qcEnum);
        EnvCalibrationComposerIntegration composer = (EnvCalibrationComposerIntegration) composer();
        Map<Long, Object> executorMapRaw = executorMap();

        BatchResult outcome;
        // 进程内互斥临界段：并发触发（调度 fire 与手动/SDK）串行进入，双开在「检查→启动」间被闸死
        synchronized (triggerMutex) {
            // 2. N 台仪器 × N 条 qcm_record 同 batch_id 批量写入（单语句=单事务，G-BUG-10）
            String batchId = UUID.randomUUID().toString();
            // §4.0.1 触发请求标识：受理时生成，返回调用方并落每条 record（受理→轮询→结果全程同一标识）
            String triggerRequestId = UUID.randomUUID().toString();
            // flow 启动时刻（flow_execution_ref = recordId@startMillis 的 millis 源）
            long flowStartMillis = System.currentTimeMillis();
            List<QcmRecord> records = buildRecords(req, source, triggerUser, qcEnum, batchId, triggerRequestId);
            recordService.insertQcmRecordBatch(records);
            List<Long> recordIds = new ArrayList<>();
            for (QcmRecord r : records) {
                recordIds.add(r.getId());
            }

            // 3. 执行器类型闸（先于互斥闸，不占 BUSY 语义）：multi_zero_check 的 ExecutorType
            //    在 composer 未接线（getEnum 会抛），不猜不降级（RED-8）——N 条记录落
            //    FAILED + EXECUTOR_TYPE_NOT_READY 终态留痕，与 BUSY 拒绝同构（FR-02-14 中间态）
            if (qcEnum == QualityControlTypeEnum.MULTI_ZERO_CHECK) {
                log.error("Calibration task exit: multi_zero_check executor type not wired yet (EXECUTOR_TYPE_NOT_READY).");
                markBatch(records, ExecutionStatusEnum.FAILED, NOT_READY_MESSAGE, NOT_READY_REASON, NOT_READY_MESSAGE);
                advancePlanAfterTrigger(req.getPlanId());
                return BatchResult.rejectedExecutorTypeNotReady(batchId, recordIds, triggerRequestId);
            }

            // 4. 互斥闸：忙 → N 条记录 FAILED + EXECUTOR_BUSY_CONFLICT 终态（D10 不 throw 留痕）
            if (Boolean.TRUE.equals(composer.isRunning())) {
                log.error("Calibration task exit: Other calibration task is currently running.");
                markBatch(records, ExecutionStatusEnum.FAILED, BUSY_CONFLICT_MESSAGE, BUSY_CONFLICT_REASON, BUSY_CONFLICT_MESSAGE);
                advancePlanAfterTrigger(req.getPlanId());
                return BatchResult.rejectedBusyConflict(batchId, recordIds, triggerRequestId);
            }
            log.info("Calibration task start, no other calibration task is currently running at the moment.");
            markBatch(records, ExecutionStatusEnum.RUNNING, RUNNING_MESSAGE, null, RUNNING_MESSAGE);

            Map<String, Object> logParams = buildLogParams(req, triggerUser);
            String instrument = req.getInstruments().get(0);
            String gasForComposer = LogicDeviceBindingIds.composerGasKeyFromParameterName(instrument);
            ExecutorType execType = ExecutorType.getEnum(qcEnum.getClassName());
            Map<String, Object> flowParams = buildFlowParams(req, qcEnum);

            CompletableFuture<ExecutorResultBase> calibrationFuture;
            try {
                if (flowParams.isEmpty()) {
                    calibrationFuture = composer.execute(execType, gasForComposer);
                } else {
                    calibrationFuture = composer.execute(execType, gasForComposer, flowParams);
                }
            } catch (Exception launchEx) {
                // 启动失败（含未知 ExecutorType 等编排前置异常）：落库终态 FAILED 留痕，不再向调用方重抛旧 RuntimeException
                log.error("Calibration task failed before async execution: {}", launchEx.getMessage(), launchEx);
                persistStubTerminal(records, formatter, logParams, qcEnum, cleanExceptionMessage(launchEx));
                advancePlanAfterTrigger(req.getPlanId());
                return BatchResult.accepted(batchId, recordIds, triggerRequestId);
            }

            AbstractCalibrationFlow executor = composer.getRunningExecutor();
            if (executor != null) {
                for (QcmRecord r : records) {
                    executorMapRaw.put(r.getId(), executor);
                }
            } else {
                log.error("Calibration task failed: No calibration task executor found.");
                markBatch(records, ExecutionStatusEnum.FAILED, NO_EXECUTOR_MESSAGE, null, NO_EXECUTOR_MESSAGE);
                advancePlanAfterTrigger(req.getPlanId());
                return BatchResult.accepted(batchId, recordIds, triggerRequestId);
            }

            attachResultCallback(calibrationFuture, records, formatter, logParams, qcEnum, executorMapRaw, flowStartMillis);
            outcome = BatchResult.accepted(batchId, recordIds, triggerRequestId);
        }

        // 5. ONCE 计划触发后 FINISHED（无论成败，D11）；所有源都更新 last_fire_time（锁外，非互斥语义）
        advancePlanAfterTrigger(req.getPlanId());
        return outcome;
    }

    /** 结果回调：thenAccept/exceptionally 平移自旧 Task（异常分支不再重抛，G-BUG-4），批次 N 行逐行落终态。 */
    @SuppressWarnings("unchecked")
    private void attachResultCallback(CompletableFuture<?> futureRaw,
                                      List<QcmRecord> records,
                                      QcResultFormatter formatter,
                                      Map<String, Object> logParams,
                                      QualityControlTypeEnum qcEnum,
                                      Map<Long, Object> executorMap,
                                      long flowStartMillis) {
        CompletableFuture<ExecutorResultBase> future = (CompletableFuture<ExecutorResultBase>) futureRaw;
        final boolean auditLikeFailureOnNotPass = qcEnum == QualityControlTypeEnum.AUDIT_SPAN_CHECK;
        // lambda 参数显式 Object（javac 会为 lambda 生成合成方法，参数类型进方法描述符——
        // 签名含 composer 类型会让 Spring 内省在 ruoyi 类加载器下 CNFE，体 内强转）
        future.thenAccept((Object resultObj) -> {
            ExecutorResultBase result = (ExecutorResultBase) resultObj;
            log.info("Calibration result " + result.toString());
            for (QcmRecord record : records) {
                // 编排器已在 result 上附带 phaseRecords，须入库，勿先 throw 否则 exceptionally 无法拿到 result
                if (result.isException()) {
                    // 裸结果（ExecutorResultBase 精确类型，非 CheckResult 子类，如零点/跨度收到的裸异常完成）
                    // 走 stub 落库：format 通道按 CheckResult 强转会 ClassCastException，phaseRecords 也会丢
                    String resultContentJson = isBareExecutorResultOutcome(result)
                            ? formatter.formatStub(core, logParams, result,
                                    record.getQualityControlType(), record.getId())
                            : formatter.format(core, logParams, result,
                            record.getQualityControlType(), record.getId());
                    String eval = result.getErrorMessage() != null && !result.getErrorMessage().isEmpty()
                            ? result.getErrorMessage()
                            : result.getResultMessage();
                    record.setExecutionLog(resultContentJson);
                    record.setResultEvaluation(eval != null ? eval : "校准过程异常");
                    record.setEndTime(recordService.resolveTerminalEndTime(record.getId()));
                    record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
                    recordService.updateQcmRecord(record);
                    freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis);
                    executorMap.remove(record.getId());
                    continue;
                }
                String resultContentJson = formatter.format(core, logParams, result,
                        record.getQualityControlType(), record.getId());
                String message;
                ExecutionStatusEnum status;
                if (result.isPass()) {
                    log.info("Calibration task completed successfully.");
                    // 标准质控沿用旧语义：完成即 SUCCESS（未通过也 SUCCESS）；人工核查：未通过 FAILED
                    message = auditLikeFailureOnNotPass
                            ? "校准任务成功完成 " + result.getResultMessage()
                            : "校准任务完成，且已通过 " + result.getResultMessage();
                    status = ExecutionStatusEnum.SUCCESS;
                } else {
                    log.error("Calibration task not pass: " + result.getResultMessage());
                    message = auditLikeFailureOnNotPass
                            ? "校准任务未通过 " + result.getResultMessage()
                            : "校准任务完成，但未通过 " + result.getResultMessage();
                    status = auditLikeFailureOnNotPass ? ExecutionStatusEnum.FAILED : ExecutionStatusEnum.SUCCESS;
                }
                record.setEndTime(Instant.now());
                record.setExecutionLog(resultContentJson);
                record.setResultEvaluation(message);
                record.setExecutionStatus(status.getCode().intValue());
                recordService.updateQcmRecord(record);
                freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis);
                executorMap.remove(record.getId());
            }
        }).exceptionally(ex -> {
            Throwable cause = ex;
            if (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof ExecutorStoppedException) {
                ExecutorStoppedException stopped = (ExecutorStoppedException) cause;
                for (QcmRecord record : records) {
                    ExecutorResultBase stopResult = stopped.toResult();
                    AbstractCalibrationFlow flow = (AbstractCalibrationFlow) executorMap.remove(record.getId());
                    stopResult.setPhaseRecords((List<PhaseExecutionRecord>) phaseRecordsOf(flow));
                    String resultContentJson = formatter.formatStub(core, logParams, stopResult,
                            record.getQualityControlType(), record.getId());
                    record.setExecutionLog(resultContentJson);
                    record.setResultEvaluation(stopResult.getErrorMessage());
                    record.setEndTime(recordService.resolveTerminalEndTime(record.getId()));
                    record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
                    recordService.updateQcmRecord(record);
                    freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis);
                }
                return null;
            }
            // G-BUG-4：异常不 throw 进 future，一律落库终态 FAILED + execution_log 留因
            // 带 full stack：thenAccept 内部抛出的异常会包成 CompletionException 进本分支，
            // 只打 message 无法定位（2026-08-23 失败分支 NPE 无栈排查实证）
            log.error("Calibration task executed exception", ex);
            String message = "校准任务过程异常 " + cleanExceptionMessage(cause);
            for (QcmRecord record : records) {
                ExecutorResultBase stub = new ExecutorResultBase(false, true);
                stub.setErrorMessage(cleanExceptionMessage(cause));
                AbstractCalibrationFlow flow = (AbstractCalibrationFlow) executorMap.remove(record.getId());
                stub.setPhaseRecords((List<PhaseExecutionRecord>) phaseRecordsOf(flow));
                String resultContentJson = formatter.formatStub(core, logParams, stub,
                        record.getQualityControlType(), record.getId());
                record.setExecutionLog(resultContentJson);
                record.setResultEvaluation(message);
                record.setEndTime(Instant.now());
                record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
                recordService.updateQcmRecord(record);
                freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis);
            }
            return null;
        });
    }

    /**
     * 完成时冻结结果快照（§4.0）：对刚落库的 execution_log JSON 解析一次
     * （result 判定标量/qcPhaseTimelines/keyParametersSnapshot/keyParametersSamplingWindow），
     * 全量喂给 {@link ResultSnapshotWriter}（避免四个回调分支各自重复解析）。
     * 失败留痕路径（启动失败/NOT_READY/冲突）不调用本方法——无执行事实可冻结。
     */
    @SuppressWarnings("unchecked")
    private void freezeSnapshotFromLog(QcmRecord record, String executionLogJson,
                                       QualityControlTypeEnum qcEnum, long flowStartMillis) {
        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(executionLogJson);
        Map<String, Object> judgement = Collections.emptyMap();
        Object resultObj = root.get("result");
        if (resultObj instanceof Map) {
            judgement = (Map<String, Object>) resultObj;
        }
        List<ResultSnapshotWriter.PhaseSnapshot> phases = new ArrayList<>();
        Object timelines = root.get(QualityControlExecutionLogHelper.QC_PHASE_TIMELINES_KEY);
        if (timelines instanceof List) {
            for (Object o : (List<?>) timelines) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) o;
                phases.add(new ResultSnapshotWriter.PhaseSnapshot(
                        strOrNull(row.get("phaseCode")),
                        strOrNull(row.get("phaseName")),
                        row.get("estimatedSeconds") instanceof Number
                                ? Integer.valueOf(((Number) row.get("estimatedSeconds")).intValue()) : null,
                        instantOrNull(row.get("startTimeMillis"), row.get("startTime")),
                        instantOrNull(row.get("endTimeMillis"), row.get("endTime"))));
            }
        }
        List<ResultSnapshotWriter.KeyParamSnapshot> keyParams = new ArrayList<>();
        Object keySnapshot = root.get("keyParametersSnapshot");
        if (keySnapshot instanceof List) {
            for (Object o : (List<?>) keySnapshot) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) o;
                // 快照行 tName/tValue/tRange 契约：单位已并入 tValue 呈现，unit 如实 null
                keyParams.add(new ResultSnapshotWriter.KeyParamSnapshot(
                        strOrNull(row.get("tName")), strOrNull(row.get("tValue")),
                        strOrNull(row.get("unit")), strOrNull(row.get("tRange"))));
            }
        }
        Instant samplingStart = null;
        Instant samplingEnd = null;
        Object win = root.get("keyParametersSamplingWindow");
        if (win instanceof Map) {
            Map<String, Object> w = (Map<String, Object>) win;
            samplingStart = w.get("startTimeMillis") instanceof Number
                    ? Instant.ofEpochMilli(((Number) w.get("startTimeMillis")).longValue()) : null;
            samplingEnd = w.get("endTimeMillis") instanceof Number
                    ? Instant.ofEpochMilli(((Number) w.get("endTimeMillis")).longValue()) : null;
        }
        snapshotWriter.freezeResultSnapshot(record.getId(), qcEnum.getClassName(), judgement,
                phases, keyParams, samplingStart, samplingEnd, flowStartMillis, record.getParameter());
    }

    private static String strOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 时间元素解析：毫秒值优先，兼容历史本地时间串（yyyy-MM-dd HH:mm:ss，系统默认时区）。 */
    private static Instant instantOrNull(Object millisObj, Object stringObj) {
        if (millisObj instanceof Number) {
            return Instant.ofEpochMilli(((Number) millisObj).longValue());
        }
        if (millisObj != null) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(String.valueOf(millisObj).trim()));
            } catch (NumberFormatException ignored) {
                // fall through 到时间串
            }
        }
        if (stringObj == null) {
            return null;
        }
        try {
            return QcmRecord.parseQueryWindow("qcPhaseTimeline", String.valueOf(stringObj));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 裸结果判定（平移自旧 Task）：精确 ExecutorResultBase 类型（非 CheckResult 等子类）。 */
    private static boolean isBareExecutorResultOutcome(Object resultObj) {
        if (!(resultObj instanceof ExecutorResultBase)) { return false; }
        ExecutorResultBase result = (ExecutorResultBase) resultObj;
        return result != null && result.getClass() == ExecutorResultBase.class;
    }

    /** flow 阶段时间线 → PhaseExecutionRecord 列表（stop/异常落库留因用）；flow 为 null 返回空表。
     * 参数 Object + 体 内强转（Spring bean 方法签名禁 composer 类型，理由见 composer()）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List phaseRecordsOf(Object flowObj) {
        List<PhaseExecutionRecord> recs = new ArrayList<>();
        if (flowObj == null) {
            return recs;
        }
        AbstractCalibrationFlow flow = (AbstractCalibrationFlow) flowObj;
        for (PhaseInfo pi : flow.getExecutorPhases()) {
            if (pi == null) {
                continue;
            }
            recs.add(new PhaseExecutionRecord(
                    pi.getId(), pi.getDisplayName(), pi.getStartInstant(), pi.getEndInstant(),
                    pi.getEstimatedSeconds()));
        }
        return recs;
    }

    /** N 仪器 × N 条 qcm_record：batch_id/task_type(source.code)/trigger_user/plan_id/快照/WAITING。 */
    private static List<QcmRecord> buildRecords(QcExecutionRequest req, TriggerSource source, String triggerUser,
                                                QualityControlTypeEnum qcEnum, String batchId,
                                                String triggerRequestId) {
        Instant now = Instant.now();
        List<QcmRecord> records = new ArrayList<>();
        for (String instrument : req.getInstruments()) {
            QcmRecord record = new QcmRecord();
            record.setBatchId(batchId);
            record.setPlanId(req.getPlanId());
            record.setTaskType(source.getCode());
            record.setQualityControlType(qcEnum.getCode());
            record.setTriggerRequestId(triggerRequestId);
            record.setFlowType(qcEnum.getClassName());
            record.setParameter(ParameterEnum.valueOf(instrument).getCode());
            record.setStartTime(now);
            record.setExecutionStatus(ExecutionStatusEnum.WAITING.getCode().intValue());
            record.setTriggerUser(triggerUser);
            record.setRecordSnapshot(req.getPlanSnapshotJson());
            record.setCreatedBy(triggerUser);
            record.setUpdatedBy(triggerUser);
            // insertBatch 全列写入：createTime/update_time 均须显式赋值（NOT NULL，无 DB 默认兜底路径）
            record.setCreateTime(now);
            record.setUpdateTime(now);
            records.add(record);
        }
        return records;
    }

    /**
     * flowParams 组装（平移自旧 Task，key 契约沿用 composer）：零点 0 / 跨度浓度 / 人工核查时长序列 / 流量。
     * 包级可见：计划预估（FR-01-32）与执行共用同一组装，保证「预估=实际计划」无双源。
     */
    public static Map<String, Object> buildFlowParams(QcExecutionRequest req, QualityControlTypeEnum qcEnum) {
        Map<String, Object> flowParams = new HashMap<>();
        String code = qcEnum.getCode();
        if (QualityControlTypeEnum.ZERO_CHECK.getCode().equals(code)) {
            flowParams.put("spanConcentrationPpb", 0f);
            if (req.getDurationOverrides() != null) {
                flowParams.putAll(req.getDurationOverrides());
            }
        } else if (QualityControlTypeEnum.SPAN_CHECK.getCode().equals(code)) {
            if (req.getDurationOverrides() != null) {
                flowParams.putAll(req.getDurationOverrides());
            }
            float spanPpb;
            if (req.getConcentrationPpb() != null) {
                spanPpb = req.getConcentrationPpb().floatValue();
            } else {
                // 旧 Task 调度入参无浓度时的固定浓度语义平移
                spanPpb = "CO".equalsIgnoreCase(req.getInstruments().get(0))
                        ? FlowDefaults.DEFAULT_SPAN_CONCENTRATION_PPB_CO
                        : FlowDefaults.DEFAULT_SPAN_CONCENTRATION_PPB;
            }
            flowParams.put("spanConcentrationPpb", spanPpb);
        } else if (QualityControlTypeEnum.AUDIT_SPAN_CHECK.getCode().equals(code)) {
            // 人工核查：时长序列从 durationOverrides（适配层已映射 stableTimeSeconds/sampleCount/sampleIntervalSeconds）
            if (req.getDurationOverrides() != null) {
                flowParams.putAll(req.getDurationOverrides());
            }
            if (req.getConcentrationPpb() != null) {
                // 适配层已换算为 ppb（旧语义 genGasConc[ppm]×1000）
                flowParams.put("spanConcentrationPpb", req.getConcentrationPpb().floatValue());
            }
        } else {
            // 多点/精密度/准确度/转换率：用户覆盖项透传；量程百分比按类型走 composer 契约 key
            if (req.getDurationOverrides() != null) {
                flowParams.putAll(req.getDurationOverrides());
            }
            if (req.getPointPercents() != null && !req.getPointPercents().isEmpty()) {
                if (QualityControlTypeEnum.MULTI_CHECK.getCode().equals(code)) {
                    flowParams.put("multiPointPercents", req.getPointPercents());
                } else if (QualityControlTypeEnum.ACCURACY_CHECK.getCode().equals(code)) {
                    flowParams.put("accuracyPointPercents", req.getPointPercents());
                }
            }
        }
        if (req.getFlowRateLpm() != null) {
            flowParams.put("flowRateLpm", req.getFlowRateLpm().floatValue());
        }
        return flowParams;
    }

    /** execution_log 的任务参数白名单源（格式化器再滤非标量）：沿用旧 Task parameters 里的标量键语义。 */
    private static Map<String, Object> buildLogParams(QcExecutionRequest req, String triggerUser) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("qualityControlType", req.getQcType());
        if (!req.getInstruments().isEmpty()) {
            params.put("parameter", req.getInstruments().get(0));
            params.put("gas", req.getInstruments().get(0));
        }
        params.put("planId", req.getPlanId());
        params.put("triggerUser", triggerUser);
        if (req.getConcentrationPpb() != null) {
            params.put("concentrationPpb", req.getConcentrationPpb());
        }
        if (req.getFlowRateLpm() != null) {
            params.put("targetFlowLpm", req.getFlowRateLpm());
        }
        if (req.getDurationOverrides() != null) {
            params.putAll(req.getDurationOverrides());
        }
        return params;
    }

    /** 批次统一落库：状态/结果评价/执行日志/结构化失败原因 + end_time=now。 */
    private void markBatch(List<QcmRecord> records, ExecutionStatusEnum status, String evaluation,
                           String failureReason, String executionLog) {
        Instant now = Instant.now();
        for (QcmRecord record : records) {
            record.setEndTime(now);
            record.setExecutionStatus(status.getCode().intValue());
            record.setResultEvaluation(evaluation);
            record.setFailureReason(failureReason);
            record.setExecutionLog(executionLog);
            recordService.updateQcmRecord(record);
        }
    }

    /** 启动失败桩结果落库（格式化器走 stub 通道，避免零点/跨度 (CheckResult) 强转失败）。 */
    private void persistStubTerminal(List<QcmRecord> records, QcResultFormatter formatter,
                                     Map<String, Object> logParams, QualityControlTypeEnum qcEnum,
                                     String cleanMessage) {
        String message = "校准任务过程异常 " + cleanMessage;
        for (QcmRecord record : records) {
            ExecutorResultBase stub = new ExecutorResultBase(false, true);
            stub.setErrorMessage(cleanMessage);
            record.setExecutionLog(formatter.formatStub(core, logParams, stub, qcEnum.getCode(), record.getId()));
            record.setResultEvaluation(message);
            record.setEndTime(Instant.now());
            record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
            recordService.updateQcmRecord(record);
        }
    }

    /**
     * 触发后计划推进（D11）：ONCE → FINISHED（无论成败/冲突）；非 ONCE 不动 next_fire_time（调度器自理），
     * 仅回写 last_fire_time；planId 为 null（SDK 直接触发）或计划已删时跳过。
     */
    private void advancePlanAfterTrigger(Long planId) {
        if (planId == null) {
            return;
        }
        QcmPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            log.warn("[诊断调试] qcm 计划 {} 触发后推进时行已不存在，跳过", planId);
            return;
        }
        Instant now = Instant.now();
        if ("ONCE".equals(plan.getScheduleType())) {
            planMapper.updateNextFireTime(planId, null, now);
            planMapper.updateStatus(planId, "FINISHED", ORCHESTRATOR_ACTOR);
        } else {
            planMapper.updateLastFireTime(planId, now, ORCHESTRATOR_ACTOR);
        }
    }

    private QcResultFormatter formatterFor(QualityControlTypeEnum qcEnum) {
        for (QcResultFormatter formatter : formatters) {
            if (formatter.supports(qcEnum.getCode())) {
                return formatter;
            }
        }
        throw new IllegalStateException("无可用结果格式化器（集成入口未注册），质控类型: " + qcEnum.getName());
    }

    /** 返回 Object（签名不得引用 composer 类型：Spring 内省在 ruoyi 类加载器下解析签名会 CNFE），调用点体内强转。 */
    private Object composer() {
        return core.getIntegrationRegistry().getIntegration(COMPOSER_INTEGRATION_ID);
    }

    /** 值类型 Object（签名无 composer 类型，理由同 composer()）；取用点强转 AbstractCalibrationFlow。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<Long, Object> executorMap() {
        EnvQualityControlManagerIntegration entry = (EnvQualityControlManagerIntegration)
                core.getIntegrationRegistry().getIntegration(SELF_INTEGRATION_ID);
        return (Map) entry.executorMap;
    }

    private static String cleanExceptionMessage(Throwable ex) {
        String message = ex.getMessage();
        return message != null ? message.replaceAll("^(java\\.lang\\.[A-Za-z]+: )", "") : "";
    }
}
