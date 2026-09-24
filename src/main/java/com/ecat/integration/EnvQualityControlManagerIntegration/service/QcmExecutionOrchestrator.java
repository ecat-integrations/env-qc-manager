package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.EcatCore;
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
import com.ecat.integration.EnvQualityControlManagerIntegration.util.CylinderArchiveSupport;
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
 *
 * <p>字节码隔离边界：本类是 ruoyi 延迟加载路径上的 Spring bean，注册后立即被 preInstantiateSingletons
 * 内省（触发 JVM 链接+类型校验），彼时 composer 集成（独立 jar）往往尚未进共享 loader——
 * 本类字节码（含方法体局部变量帧/checkcast/方法描述符）不得引用任何 composer 类型，否则
 * 校验强制解析即 NoClassDefFoundError、bean 创建失败级联拖垮执行链。全部 composer 触碰段
 * （执行启动/停止/结果回调/逐气分发/桩结果）收敛在非 bean 协作类 {@link ComposerExecutionBridge}
 * （首执行才加载，彼时 composer 必已就位），经其方法签名只传 Object/JDK/qcm 类型。</p>
 *
 * @author coffee
 */
@Service
public class QcmExecutionOrchestrator {

    static final String BUSY_CONFLICT_REASON = "EXECUTOR_BUSY_CONFLICT";
    static final String BUSY_CONFLICT_MESSAGE = "校准任务退出 已有执行中的校准任务";
    /**
     * 历史结构化失败原因（multi_zero_check 接线前的闸前拒绝留痕）：multi 已解闸受理，编排器
     * 不再产生该状态；词汇保留供既有台账行与拒绝回执的文案映射（SDK 词汇翻译同源）。
     */
    static final String NOT_READY_REASON = "EXECUTOR_TYPE_NOT_READY";
    static final String NOT_READY_MESSAGE = "多仪器零点 flow 未接线";
    /** 缺监测仪降级运行的结构化失败原因（人工视检，监测数据无效；终态 SUCCESS + is_pass=false 留痕进报告）。 */
    static final String TARGET_ANALYZER_MISSING_REASON = "TARGET_ANALYZER_MISSING";
    /** 缺校准仪降级运行的结构化失败原因（外部校准仪人工操作模式，判定照常交给数据）。 */
    static final String CALIBRATOR_MISSING_REASON = "CALIBRATOR_MISSING";
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

    /**
     * 拒绝回执文案（触发方抛给任务框架，操作员在调度日志可见）：按结构化状态映射——忙 /
     * 未接线 / 预检拒是三种不同处置方向，不能一律报「已有执行中的校准任务」（会把未接线、
     * 参数非法误导成去找并不存在的执行中任务）。有固定文案的拒绝复用落库留痕同款常量；
     * PRE_TRIGGER 无固定文案，透传结构化原因码（INVALID_PARAM / QUEUE_NOT_SUPPORTED）。
     *
     * @throws IllegalStateException status 为 ACCEPTED——本方法只对拒绝回执有意义，
     *                               受理结果走成功路径，调到这里是调用方分支写错
     */
    public static String rejectMessageOf(BatchResult result) {
        switch (result.getStatus()) {
            case REJECTED_BUSY_CONFLICT:
                return BUSY_CONFLICT_MESSAGE;
            case REJECTED_EXECUTOR_TYPE_NOT_READY:
                return NOT_READY_MESSAGE;
            case REJECTED_PRE_TRIGGER:
                return "校准任务退出 " + result.getFailureReason();
            default:
                throw new IllegalStateException("ACCEPTED 不是拒绝状态，无拒绝文案：" + result.getStatus());
        }
    }

    private static final String COMPOSER_INTEGRATION_ID = "integration-env-calibration-composer";
    private static final String SELF_INTEGRATION_ID = "integration-env-qc-manager";

    private static final Logger log = LoggerFactory.getLogger(QcmExecutionOrchestrator.class);

    /** 协作件对同包 bridge 开放（包私有）：bean 类自身不做 composer 触碰，落库细节由 bridge 复用本类件。 */
    final IQcmRecordService recordService;
    private final QcmPlanMapper planMapper;
    private final QcmRecordMapper recordMapper;
    final EcatCore core;
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
     *         N 条记录已写 FAILED+EXECUTOR_BUSY_CONFLICT 终态留痕）
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
        // composer 实例与 flow 句柄全程以 Object 流转（字节码隔离边界，见类注释）
        Object composer = composer();
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

            // 3. 互斥闸：忙 → N 条记录 FAILED + EXECUTOR_BUSY_CONFLICT 终态（D10 不 throw 留痕）
            if (ComposerExecutionBridge.isRunning(composer)) {
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
            // 执行器枚举解析维持 try 外原位：未知质控类型的解析失败语义（向调用方抛出）与内联时期一致
            Object execType = ComposerExecutionBridge.executorTypeOf(qcEnum);
            Map<String, Object> flowParams = buildFlowParams(req, qcEnum);

            Object calibrationFuture;
            try {
                calibrationFuture = ComposerExecutionBridge.execute(composer, execType, gasForComposer, flowParams);
            } catch (Exception launchEx) {
                // 启动失败（含未知 ExecutorType 等编排前置异常）：落库终态 FAILED 留痕，不再向调用方重抛旧 RuntimeException
                log.error("Calibration task failed before async execution: {}", launchEx.getMessage(), launchEx);
                ComposerExecutionBridge.persistStubTerminal(this, records, formatter, logParams, qcEnum,
                        cleanExceptionMessage(launchEx));
                advancePlanAfterTrigger(req.getPlanId());
                return BatchResult.accepted(batchId, recordIds, triggerRequestId);
            }

            Object executor = ComposerExecutionBridge.runningExecutor(composer);
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

            ComposerExecutionBridge.attachResultCallback(this, calibrationFuture, records, formatter, logParams,
                    qcEnum, executorMapRaw, flowStartMillis);
            outcome = BatchResult.accepted(batchId, recordIds, triggerRequestId);
        }

        // 4. ONCE 计划触发后 FINISHED（无论成败，D11）；所有源都更新 last_fire_time（锁外，非互斥语义）
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
        Object flow = firstFlowOf(rowIds, executorMap);
        if (flow != null) {
            if (target.getBatchId() != null) {
                // 停止者暂存先于 stop 写入：stop 会触发（甚至同步触发）终态回调，回调必须读得到停止者
                stopOperatorsByBatch.put(target.getBatchId(), displayOperator);
            }
            // 先停后清（缺陷 #2）：停止回调从 executorMap 取 flow 解析阶段时间线，先摘除会得空时间线
            ComposerExecutionBridge.stopFlow(flow);
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
     *  值类型 Object（字节码隔离边界，见类注释）；stop 经 bridge 强转。 */
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

    /**
     * 取走本批的停止者暂存（读后清）：调用点在终态回调体内——displayOperator 由 stopExecution
     * 在受理时写入，回调发生在其后的任意时刻与线程，必须回调时读取而非回调装配时。
     * 无 batch_id 的历史行没有暂存槽位（stopExecution 同样只对有批次的行暂存），返回 null 属正常。
     * 包私有：bridge 结果回调消费。
     */
    String takeStopOperator(List<QcmRecord> records) {
        String batchId = records.get(0).getBatchId();
        return batchId == null ? null : stopOperatorsByBatch.remove(batchId);
    }

    /**
     * 完成时冻结结果快照（§4.0）：对刚落库的 execution_log JSON 解析一次
     * 完成时冻结结果快照（§4.0）：对刚落库的 execution_log JSON 解析一次
     * （result 判定标量/qcPhaseTimelines/keyParametersSnapshot/keyParametersSamplingWindow），
     * 全量喂给 {@link ResultSnapshotWriter}（避免四个回调分支各自重复解析）。
     * 失败留痕路径（启动失败/NOT_READY/冲突）不调用本方法——无执行事实可冻结。
     * 包私有：bridge 各结果分支复用（避免四个回调分支各自重复解析同构冻结逻辑）。
     */
    @SuppressWarnings("unchecked")
    void freezeSnapshotFromLog(QcmRecord record, String executionLogJson,
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

    /**
     * N 仪器 × N 条 qcm_record：batch_id/task_type(source.code)/trigger_user/plan_id/快照/WAITING，
     * 外加钢瓶气一本账受理定格（gas 四列）。拒绝留痕（persistRejectedBatch）同走此组装——
     * 拒绝行带 gas 值是有意为之：一行台账记的是「当时要用的什么气」，与执行成败无关。
     */
    private List<QcmRecord> buildRecords(QcExecutionRequest req, TriggerSource source, String triggerUser,
                                         QualityControlTypeEnum qcEnum, String batchId,
                                         String triggerRequestId) {
        Instant now = Instant.now();
        List<QcmRecord> records = new ArrayList<>();
        for (String instrument : req.getInstruments()) {
            QcmRecord record = new QcmRecord();
            record.setBatchId(batchId);
            record.setPlanId(req.getPlanId());
            record.setTaskType(source.getCode());
            // multi 台账行落零点码：与单气零点同报表配对域（配对/统计按码聚合），multi 身份由
            // flow_type 溯源（air.monitor.calibration.multi_zero_check）
            record.setQualityControlType(qcEnum == QualityControlTypeEnum.MULTI_ZERO_CHECK
                    ? QualityControlTypeEnum.ZERO_CHECK.getCode() : qcEnum.getCode());
            record.setTriggerRequestId(triggerRequestId);
            record.setFlowType(qcEnum.getClassName());
            String gasCode = ParameterEnum.valueOf(instrument).getCode();
            record.setParameter(gasCode);
            record.setStartTime(now);
            record.setExecutionStatus(ExecutionStatusEnum.WAITING.getCode().intValue());
            record.setTriggerUser(triggerUser);
            record.setRecordSnapshot(req.getPlanSnapshotJson());
            record.setCreatedBy(triggerUser);
            record.setUpdatedBy(triggerUser);
            // insertBatch 全列写入：createTime/update_time 均须显式赋值（NOT NULL，无 DB 默认兜底路径）
            record.setCreateTime(now);
            record.setUpdateTime(now);
            freezeGasLedgerAtAcceptance(record, gasCode, instrument);
            records.add(record);
        }
        return records;
    }

    /**
     * 钢瓶气一本账受理定格（2026-09-18 定案）：受理时刻读一次档案定格 gas 四列入行，
     * 此后完成冻结不再读写（换瓶不影响已受理行的历史值）。权威源=站房 standard_gas
     * 逻辑设备；浓度与单位成对完整性——缺一个则两个都留空（杜绝裸数字被报表层贴默认单位），
     * 来源/编号各自独立有就存。O3（发生器供气）/设备未注册/读取失败 → 四列如实 null：
     * 没有就是没有，不阻碍 flow 执行。
     */
    private void freezeGasLedgerAtAcceptance(QcmRecord record, String gasCode, String instrument) {
        CylinderArchiveSupport.GasTrace trace;
        try {
            trace = CylinderArchiveSupport.readArchive(core, gasCode);
        } catch (Exception e) {
            log.warn("钢瓶气一本账受理定格读取失败，四列写空继续受理：instrument={}", instrument, e);
            return;
        }
        if (trace == null) {
            return;
        }
        record.setGasSource(trace.gasSource);
        record.setGasNo(trace.cylinderId);
        if (trace.concentration != null && trace.concentrationUnit != null) {
            record.setGasConcentration(trace.concentration);
            record.setGasConcentrationUnit(trace.concentrationUnit);
        }
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
            putCalibrationPolicyIfPresent(flowParams, req.getCalibrationPolicy());
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
            putCalibrationPolicyIfPresent(flowParams, req.getCalibrationPolicy());
        } else if (QualityControlTypeEnum.AUDIT_SPAN_CHECK.getCode().equals(code)) {
            // 人工核查：时长序列从 durationOverrides（适配层已映射 stableTimeSeconds/sampleCount/sampleIntervalSeconds）
            if (req.getDurationOverrides() != null) {
                flowParams.putAll(req.getDurationOverrides());
            }
            if (req.getConcentrationPpb() != null) {
                // 适配层已换算为 ppb（旧语义 genGasConc[ppm]×1000）
                flowParams.put("spanConcentrationPpb", req.getConcentrationPpb().floatValue());
            }
        } else if (QualityControlTypeEnum.MULTI_ZERO_CHECK.getCode().equals(code)) {
            // 多仪器同时零点（composer 契约）：instruments=gas key 列表 List 直传（序=仪器序=信封
            // 子结果 key 序，勿 JSON 化——composer 按 List<String> 读取）；零点浓度恒 0；流量缺省 5
            // （计划行不预填）；校准策略非空透传（null 不落键=composer 缺省 STANDARD）
            List<String> gasKeys = new ArrayList<>(req.getInstruments().size());
            for (String instrument : req.getInstruments()) {
                gasKeys.add(LogicDeviceBindingIds.composerGasKeyFromParameterName(instrument));
            }
            if (req.getDurationOverrides() != null) {
                flowParams.putAll(req.getDurationOverrides());
            }
            flowParams.put("spanConcentrationPpb", 0f);
            flowParams.put("instruments", gasKeys);
            putCalibrationPolicyIfPresent(flowParams, req.getCalibrationPolicy());
            flowParams.put("flowRateLpm", req.getFlowRateLpm() != null
                    ? req.getFlowRateLpm().floatValue() : 5f);
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

    /**
     * 校准策略非空落键（null/空白不落 = STANDARD 缺省语义），两处共用同一缺省规则：
     * flowParams 侧——零点/跨度/同时零点三 flow 透传 composer（人工核查 audit_span_check
     * 按验收 A6 不接该参数，只在对应分支调用、不在此处判断类型）；
     * logParams 侧——execution_log 任务参数留痕（A7，事后可反推当时策略）。
     */
    private static void putCalibrationPolicyIfPresent(Map<String, Object> params, String policy) {
        if (policy != null && !policy.trim().isEmpty()) {
            params.put("calibrationPolicy", policy);
        }
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
        // A7 留痕：策略非空才落 execution_log 任务参数（事后可反推当时策略；null 不落=STANDARD 缺省语义）
        putCalibrationPolicyIfPresent(params, req.getCalibrationPolicy());
        if (req.getDurationOverrides() != null) {
            params.putAll(req.getDurationOverrides());
        }
        return params;
    }

    /** 批次统一落库：状态/结果评价/执行日志/结构化失败原因；end_time 仅终态行写 now。
     *  RUNNING 受理行 endTime 留空（end_time 列义=结束时刻，运行中行没有），由终态回调
     *  （bridge 各终态路径 setEndTime）或终止 SQL 回填；不设 endTime 时 mapper 条件写
     *  天然不动 DB 的 end_time 列（insert 侧本就 null）。 */
    private void markBatch(List<QcmRecord> records, ExecutionStatusEnum status, String evaluation,
                           String failureReason, String executionLog) {
        Instant now = Instant.now();
        // 终态集（成功/失败/让位）与台账读侧「已结束」口径一致；WAITING/STOPPING 不经本方法，
        // 若未来流入也属非终态、不该带 endTime，故无 else 补写
        boolean terminal = status == ExecutionStatusEnum.SUCCESS
                || status == ExecutionStatusEnum.FAILED
                || status == ExecutionStatusEnum.SKIPPED;
        for (QcmRecord record : records) {
            if (terminal) {
                record.setEndTime(now);
            }
            record.setExecutionStatus(status.getCode().intValue());
            record.setResultEvaluation(evaluation);
            record.setFailureReason(failureReason);
            record.setExecutionLog(executionLog);
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
            log.warn("qcm 计划 {} 触发后推进时行已不存在，跳过", planId);
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

    /** 返回 Object（字节码隔离边界：本 bean 类常量池不得出现 composer 类型名），bridge 体内强转。 */
    private Object composer() {
        return core.getIntegrationRegistry().getIntegration(COMPOSER_INTEGRATION_ID);
    }

    /** 值类型 Object（字节码隔离边界，理由同 composer()）；句柄的强转/停止经 bridge。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<Long, Object> executorMap() {
        EnvQualityControlManagerIntegration entry = (EnvQualityControlManagerIntegration)
                core.getIntegrationRegistry().getIntegration(SELF_INTEGRATION_ID);
        return (Map) entry.executorMap;
    }

    /** 包私有静态：bridge 异常分支与启动失败留痕共用同一清洗规则。 */
    static String cleanExceptionMessage(Throwable ex) {
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

    /**
     * 同日优先级让位留痕：SCHEDULED 链路被高优先级计划覆盖时不调 composer（不查忙闸、不占
     * 互斥闸），按正常台账形状受理后整批直接收敛 SKIPPED 终态——N 仪器 N 条、气种 parameter、
     * gas 四列受理定格与执行受理同一语义（让位行记的也是「当时要用的什么气」）。触发源与
     * 触发者在本方法内固定为 SCHEDULED/system：让位只发生在调度链路，不把 source/user 开放成
     * 调用面防误用。next_fire 推进由调度器 fireInternal 统一的 advancePlan 承担，此处不参与。
     *
     * @param suppressedByPlanName 压制方计划名（写入 result_evaluation，留痕注明被谁覆盖）
     */
    public void skipExecution(QcExecutionRequest req, String suppressedByPlanName) {
        if (req == null || req.getQcType() == null || req.getQcType().trim().isEmpty()) {
            throw new IllegalArgumentException("qcType is required to persist skipped quality control batch");
        }
        if (req.getInstruments() == null || req.getInstruments().isEmpty()) {
            throw new IllegalArgumentException("instruments is required to persist skipped quality control batch");
        }
        if (suppressedByPlanName == null || suppressedByPlanName.trim().isEmpty()) {
            throw new IllegalArgumentException("suppressedByPlanName is required to persist skipped quality control batch");
        }
        QualityControlTypeEnum qcEnum = QualityControlTypeEnum.valueOf(req.getQcType().trim().toUpperCase());
        String evaluation = "同日让位：当日已由高优先级计划「" + suppressedByPlanName + "」覆盖同类检查";
        String batchId = UUID.randomUUID().toString();
        List<QcmRecord> records = buildRecords(req, TriggerSource.SCHEDULED, "system", qcEnum, batchId,
                UUID.randomUUID().toString());
        recordService.insertQcmRecordBatch(records);
        // 与拒绝留痕同构：无执行事实，execution_log 承载同一说明，不冻结快照
        markBatch(records, ExecutionStatusEnum.SKIPPED, evaluation, "SUPPRESSED_BY_PRIORITY", evaluation);
    }
}
