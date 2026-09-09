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
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.StopOutcome;
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
import java.util.concurrent.ConcurrentHashMap;

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
    /**
     * composer 被停结果的结果评定原文（ExecutorStoppedException.toResult() 的固定 errorMessage）。
     * composer 侧把停止异常经 handle 转成结果对象完成 future，异常类型信息丢失，
     * 该文案是「这是被停而非执行失败」的唯一可辨标记——仅在有停止者暂存时用它换算来源文案。
     */
    static final String COMPOSER_STOP_EVALUATION = "流程被用户手动终止";

    private static final String COMPOSER_INTEGRATION_ID = "integration-env-calibration-composer";
    private static final String SELF_INTEGRATION_ID = "integration-env-qc-manager";

    private static final Logger log = LoggerFactory.getLogger(QcmExecutionOrchestrator.class);

    private final IQcmRecordService recordService;
    private final QcmPlanMapper planMapper;
    private final QcmRecordMapper recordMapper;
    private final EcatCore core;
    private final ResultSnapshotWriter snapshotWriter;

    /**
     * 进程内互斥闸锁：composer 无内建互斥（execute 不拒绝并发，直接覆盖 runningFlow 成不可 stop 的孤儿 flow），
     * 此锁是唯一防线；SCHEDULED/MANUAL/REMOTE 三源都经 triggerExecution 收敛于此。
     * 临界段 = 「互斥检查→创建记录→execute 异步启动」（不含结果回调——回调在锁外由 future 线程驱动）。
     */
    private final Object triggerMutex = new Object();

    /**
     * 停止者批次级暂存（来源契约 §6 / 行内留痕矩阵 §7）：displayOperator 在停止受理时才可知，
     * 而停止文案由结果回调在 composer future 线程写出——跨线程传递只能走线程安全暂存。
     * stopExecution 受理路径在 flow.stop() 之前写入（回调可能被 stop 同步触发，暂存必须先可见），
     * 回调读后清（本批终态回调只执行一次），未受理（守卫 0 行）即撤回，不残留。
     */
    private final ConcurrentHashMap<String, String> stopOperatorsByBatch = new ConcurrentHashMap<>();

    /** 结果格式化器（标准质控/人工核查两套 constructResult），集成入口 onStart 注册 */
    private volatile List<QcResultFormatter> formatters = Collections.emptyList();

    @Autowired
    public QcmExecutionOrchestrator(IQcmRecordService recordService, QcmPlanMapper planMapper,
                                     QcmRecordMapper recordMapper, EcatCore core,
                                     ResultSnapshotWriter snapshotWriter) {
        this.recordService = recordService;
        this.planMapper = planMapper;
        this.recordMapper = recordMapper;
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

    /**
     * 三源统一停止入口（设计 §8 核心上移）：REST（{@code QcmRecordServiceImpl} 薄壳）与 SDK
     * （{@code QualityControlSdkImpl}）共用同一停止决策链，防两份拷贝分叉。批次粒度——
     * 一个批次一个 flow，寻址到批内任一行即停整批。受理即异步：本方法只做 flow.stop() + STOPPING 置位
     * （或无运行 flow 时 set-based 直接收敛），设备恢复与终态落库由结果回调完成。
     *
     * <p>幂等与竞态沿用守卫 SQL 语义（三条 stop 链 UPDATE 带 AND execution_status IN (0,1)）：
     * 0 行受影响 = 批次已结算（已终态/已在终止中/回调刚落终态的竞态窗口），如实回 ALREADY_SETTLED
     * 不动库——已终态行的 end_time/result_evaluation 是执行事实，禁止覆盖（缺陷 #1/#4）。</p>
     *
     * @param recordId         按记录寻址（批内任一行）；与 batchId/triggerRequestId/allRunning 恰好给一个
     * @param batchId          按批次寻址
     * @param triggerRequestId 按触发请求寻址（同一受理的 N 行共享，任取一行定位批次）
     * @param allRunning       全停：单飞语义下 = 停当前唯一运行批次；无运行 → NOTHING_RUNNING（非故障）
     * @param displayOperator  停止者留痕（REST=登录账号 / SDK=来源拼平），落 updated_by 与停止终态文案
     * @return 停止结果（status + 与既有 REST 停止文案逐字一致的人读消息 + 已解析批次定位）
     */
    public StopOutcome stopExecution(Long recordId, String batchId, String triggerRequestId,
                                     boolean allRunning, String displayOperator) {
        if (displayOperator == null || displayOperator.trim().isEmpty()) {
            throw new IllegalArgumentException("displayOperator is required to stop quality control execution");
        }
        // 寻址恰好一键：全空 = 没说要停什么；多键同传 = 语义含糊。两类都不猜、不擅自挑一个用
        int addressingKeys = (recordId != null ? 1 : 0)
                + (batchId != null && !batchId.trim().isEmpty() ? 1 : 0)
                + (triggerRequestId != null && !triggerRequestId.trim().isEmpty() ? 1 : 0)
                + (allRunning ? 1 : 0);
        if (addressingKeys != 1) {
            return StopOutcome.invalidParam("寻址字段必须且只能提供一个：recordId/batchId/triggerRequestId/allRunning");
        }
        QcmRecord target;
        if (recordId != null) {
            target = recordMapper.selectStopTargetById(recordId);
        } else if (batchId != null) {
            target = recordMapper.selectStopTargetByBatchId(batchId);
        } else if (triggerRequestId != null) {
            target = recordMapper.selectStopTargetByTriggerRequestId(triggerRequestId);
        } else {
            target = runningTarget();
            if (target == null) {
                return StopOutcome.nothingRunning("当前没有运行中的质控执行");
            }
        }
        if (target == null) {
            return StopOutcome.notFound(recordId != null ? "质控记录不存在" : "批次不存在");
        }
        List<Long> rowIds = target.getBatchId() != null
                ? recordMapper.selectBatchRowIds(target.getBatchId())
                : Collections.singletonList(target.getId());
        Map<Long, Object> executorMap = executorMap();
        log.info("stopExecution:target={},batchId={},rows={},executorMap={}",
                target.getId(), target.getBatchId(), rowIds.size(), executorMap.size());
        Instant now = Instant.now();
        // 同批 N 行共享同一 flow：先窥视不摘除，stop 只调一次
        AbstractCalibrationFlow flow = (AbstractCalibrationFlow) firstFlowOf(rowIds, executorMap);
        if (flow != null) {
            if (target.getBatchId() != null) {
                // 停止者暂存先于 stop 写入：stop 会触发（甚至同步触发）终态回调，回调必须读得到停止者
                stopOperatorsByBatch.put(target.getBatchId(), displayOperator);
            }
            // 先停后清（缺陷 #2）：停止回调从 executorMap 取 flow 解析阶段时间线，先摘除会得空时间线
            flow.stop();
            int marked = 0;
            for (Long rowId : rowIds) {
                marked += recordMapper.markStopInProgressClearEndTime(
                        rowId, ExecutionStatusEnum.STOPPING.getCode().intValue(), now, displayOperator);
            }
            // flow 已停，批次残留句柄一律摘除（回调若已同步收敛则此处为空操作）
            for (Long rowId : rowIds) {
                executorMap.remove(rowId);
            }
            if (marked == 0) {
                // 守卫 SQL 0 行受影响 = 批次已结算（回调刚落终态而 executorMap 未清空的竞态窗口）：
                // 不能宣称「已中止」，更不得再补 terminate 写覆盖终态数据；撤回回调未消费的停止者暂存
                log.warn("stopExecution:target={} flow 已停但批次行均为终态（回调已收敛），不改写", target.getId());
                if (target.getBatchId() != null) {
                    stopOperatorsByBatch.remove(target.getBatchId());
                }
                return StopOutcome.alreadySettled(settledMessage(target), target.getBatchId(), rowIds);
            }
            log.info("stopExecution:开启终止成功");
            return StopOutcome.initiated("质控记录已中止", target.getBatchId(), rowIds);
        }
        // G-BUG-2 收敛：记录不在 executorMap（已结束/异常残留）→ 直接置终止终态，不留 STOPPING 挂死
        log.warn("stopExecution:target={} 无运行执行器，直接收敛为终止终态", target.getId());
        String evaluation = "质控已中止（无运行执行器，直接收敛）";
        int terminated;
        if (target.getBatchId() != null) {
            terminated = recordMapper.terminateByBatchId(target.getBatchId(),
                    ExecutionStatusEnum.FAILED.getCode().intValue(), evaluation, now, displayOperator);
        } else {
            terminated = recordMapper.terminateById(target.getId(),
                    ExecutionStatusEnum.FAILED.getCode().intValue(), evaluation, now, displayOperator);
        }
        if (terminated == 0) {
            // 守卫 SQL 0 行受影响 = 本就终态/已在终止中（幂等停止）：不动库，如实回执
            log.info("stopExecution:target={} 批次无待收敛行（已终态或已在终止中），不动库", target.getId());
            return StopOutcome.alreadySettled(settledMessage(target), target.getBatchId(), rowIds);
        }
        return StopOutcome.initiated(evaluation, target.getBatchId(), rowIds);
    }

    /**
     * 当前运行批次标识（SDK queryRunning 用）：单飞语义下至多一个，空闲返回 null。
     */
    public String runningBatchId() {
        QcmRecord running = runningTarget();
        return running != null ? running.getBatchId() : null;
    }

    /**
     * 当前运行批次定位（allRunning 寻址与 {@link #runningBatchId()} 共用）：executorMap 非空即唯一
     * 运行批次，任取一行反查其批次轻量行；空闲返回 null。运行中行的记录行不存在属执行状态自洽性
     * 被破坏（运行中行被外部删除），如实硬抛暴露，不以「无运行」掩盖。
     */
    private QcmRecord runningTarget() {
        Map<Long, Object> executorMap = executorMap();
        if (executorMap.isEmpty()) {
            return null;
        }
        Long runningRecordId = executorMap.keySet().iterator().next();
        QcmRecord target = recordMapper.selectStopTargetById(runningRecordId);
        if (target == null) {
            throw new IllegalStateException("运行中执行的质控记录行不存在: recordId=" + runningRecordId);
        }
        return target;
    }

    /** 批次行内窥视第一个挂着的执行器句柄（不摘除；同批 N 行共享同一实例，stop 只调一次）。
     *  值类型 Object（签名无 composer 类型，理由同 composer()）；取用点强转 AbstractCalibrationFlow。 */
    private static Object firstFlowOf(List<Long> rowIds, Map<Long, Object> executorMap) {
        for (Long rowId : rowIds) {
            Object candidate = executorMap.get(rowId);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 已结算批次的幂等回执文案（B 已立语义原样上移）：STOPPING 是「正在终止」不是「已结束」，
     * 终态（SUCCESS/FAILED）才是「已结束」，按批次实际状态分流，不说假话。
     */
    private static String settledMessage(QcmRecord target) {
        if (target.getExecutionStatus() != null
                && target.getExecutionStatus() == ExecutionStatusEnum.STOPPING.getCode().intValue()) {
            return "批次正在终止，无需重复中止";
        }
        return "批次已结束，无需中止";
    }

    /** 停止终态文案（行内留痕矩阵 §7）：把操作者带进「被停」语义，来源可辨（缺陷 #3）。 */
    private static String stopEvaluation(String displayOperator) {
        return "流程被 " + displayOperator + " 手动终止";
    }

    /**
     * 取走本批的停止者暂存（读后清）：调用点在终态回调体内——displayOperator 由 stopExecution
     * 在受理时写入，回调发生在其后的任意时刻与线程，必须回调时读取而非回调装配时。
     * 无 batch_id 的历史行没有暂存槽位（stopExecution 同样只对有批次的行暂存），返回 null 属正常。
     */
    private String takeStopOperator(List<QcmRecord> records) {
        String batchId = records.get(0).getBatchId();
        return batchId == null ? null : stopOperatorsByBatch.remove(batchId);
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
            // 停止者暂存须在回调执行时（而非本方法装配时）读取：stop 发生在受理之后的任意时刻，
            // 读后清保证本批终态回调只消费一次（thenAccept 体抛异常会再进 exceptionally，取到 null 不重复换算）
            final String stopOperator = takeStopOperator(records);
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
                    // 停止者留痕（缺陷 #3）：composer 把停止异常经 handle 转成结果对象完成 future，
                    // 本分支才是被停的真实落库通道；仅「原文案 + 本批有停止者暂存」双条件成立才换算，
                    // 普通执行失败（同分支落库）不得冒名成被停
                    boolean stoppedByOperator = stopOperator != null && COMPOSER_STOP_EVALUATION.equals(eval);
                    if (stoppedByOperator) {
                        eval = stopEvaluation(stopOperator);
                        // 行内留痕矩阵 §7：updated_by 同落 displayOperator，
                        // 压掉创建期残留在 record 对象上、会被 update 原样回写的旧 updatedBy
                        record.setUpdatedBy(stopOperator);
                    }
                    record.setExecutionLog(resultContentJson);
                    record.setResultEvaluation(eval != null ? eval : "校准过程异常");
                    record.setEndTime(recordService.resolveTerminalEndTime(record.getId()));
                    record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
                    recordService.updateQcmRecord(record);
                    // 冻结晚于通用 update：停止行必须把操作者穿透进冻结层，否则 updated_by 被覆写回系统账号
                    freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis,
                            stoppedByOperator ? stopOperator : null);
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
                freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis, null);
                executorMap.remove(record.getId());
            }
        }).exceptionally(ex -> {
            Throwable cause = ex;
            if (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            final String stopOperator = takeStopOperator(records);
            if (cause instanceof ExecutorStoppedException) {
                ExecutorStoppedException stopped = (ExecutorStoppedException) cause;
                for (QcmRecord record : records) {
                    ExecutorResultBase stopResult = stopped.toResult();
                    if (stopOperator != null) {
                        // 停止者留痕（缺陷 #3）：异常分支可辨「确为被停」，直接换算来源文案；
                        // updated_by 同步落 displayOperator（行内留痕矩阵 §7）
                        stopResult.setErrorMessage(stopEvaluation(stopOperator));
                        record.setUpdatedBy(stopOperator);
                    }
                    AbstractCalibrationFlow flow = (AbstractCalibrationFlow) executorMap.remove(record.getId());
                    stopResult.setPhaseRecords((List<PhaseExecutionRecord>) phaseRecordsOf(flow));
                    String resultContentJson = formatter.formatStub(core, logParams, stopResult,
                            record.getQualityControlType(), record.getId());
                    record.setExecutionLog(resultContentJson);
                    record.setResultEvaluation(stopResult.getErrorMessage());
                    record.setEndTime(recordService.resolveTerminalEndTime(record.getId()));
                    record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
                    recordService.updateQcmRecord(record);
                    // 冻结晚于通用 update：停止行把操作者穿透进冻结层（行内留痕矩阵 §7）
                    freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis, stopOperator);
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
                freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis, null);
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
                                       QualityControlTypeEnum qcEnum, long flowStartMillis,
                                       String terminalActor) {
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
                phases, keyParams, samplingStart, samplingEnd, flowStartMillis, record.getParameter(),
                terminalActor);
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

    /**
     * 触发前拒绝留痕（行内留痕矩阵 §7）：参数完整但非法（INVALID_PARAM）/排队不支持
     * （QUEUE_NOT_SUPPORTED）的拒绝也与 BUSY_CONFLICT 同构建 N 条 FAILED 终态行——
     * 「qcm_record=执行尝试」语义下可归属的触发尝试一律留痕，拒绝原因落 failure_reason、
     * 逐字段错误落 result_evaluation。残缺请求（qcType/instruments 不可解析，五个 NOT NULL
     * 业务列填不出）由调用方 reply 拒绝，不进本方法。
     *
     * <p>只写库不启动执行：不经互斥闸、不触 composer（调用方已判定的拒绝，不存在并发窗口）。</p>
     *
     * @param req           执行请求（qcType/instruments 须可解析，此处防御性复校非空，
     *                      仪器可解析性由 {@code ParameterEnum.valueOf} 硬抛兜底）
     * @param source        触发源（落 qcm_record.task_type）
     * @param triggerUser   触发者留痕（REMOTE=displayOperator，PLATFORM 形态为 name@ip[:port]）
     * @param reasonCode    结构化拒绝原因（落 failure_reason，如 INVALID_PARAM / QUEUE_NOT_SUPPORTED）
     * @param message       人读消息（落 result_evaluation 与 execution_log，逐字段错误清单）
     * @return REJECTED_PRE_TRIGGER（batchId/recordIds/triggerRequestId 供调用方组回执）
     */
    public BatchResult persistRejectedBatch(QcExecutionRequest req, TriggerSource source, String triggerUser,
                                            String reasonCode, String message) {
        if (req == null || req.getQcType() == null || req.getQcType().trim().isEmpty()) {
            throw new IllegalArgumentException("qcType is required to persist rejected quality control batch");
        }
        if (req.getInstruments() == null || req.getInstruments().isEmpty()) {
            throw new IllegalArgumentException("instruments is required to persist rejected quality control batch");
        }
        if (triggerUser == null || triggerUser.trim().isEmpty()) {
            throw new IllegalArgumentException("triggerUser is required to persist rejected quality control batch");
        }
        if (reasonCode == null || reasonCode.trim().isEmpty()) {
            throw new IllegalArgumentException("reasonCode is required to persist rejected quality control batch");
        }
        QualityControlTypeEnum qcEnum = QualityControlTypeEnum.valueOf(req.getQcType().trim().toUpperCase());
        String batchId = UUID.randomUUID().toString();
        String triggerRequestId = UUID.randomUUID().toString();
        List<QcmRecord> records = buildRecords(req, source, triggerUser, qcEnum, batchId, triggerRequestId);
        recordService.insertQcmRecordBatch(records);
        List<Long> recordIds = new ArrayList<>();
        for (QcmRecord record : records) {
            recordIds.add(record.getId());
        }
        // 与 BUSY_CONFLICT 留痕同构：execution_log 承载同一拒绝消息（无执行事实，不冻结快照）
        markBatch(records, ExecutionStatusEnum.FAILED, message, reasonCode, message);
        return BatchResult.rejectedPreTrigger(batchId, recordIds, triggerRequestId, reasonCode);
    }
}
