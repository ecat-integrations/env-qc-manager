package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.api.QualityControlSdk;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.ResultFilter;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkBatchState;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkExecutionResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkOperator;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkOperatorSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkPlanSetting;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkRecordDetail;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkRunningExecution;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkStopReply;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkStopRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerReply;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordKeyParam;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPhase;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPoint;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordKeyParamMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPhaseMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPointMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.ScheduleSpec;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.ScheduleSpecs;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.StopOutcome;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.JsonUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外 SDK 实现（需求 03 / Phase 3 / §4.0 数据面）：api 包类型的内部装配器。
 *
 * <p>职责边界：参数校验完全复用 {@link PlanParamValidator#validateExecutionParams(PlanSaveDto)}
 * （FR-03-17 单一事实源，与 REST 计划保存同一套规则）；执行统一走
 * {@link QcmExecutionOrchestrator#triggerExecution}（REMOTE 源，planId=null）；
 * 数据面读强类型列+三子表组装五层结构（§4.0），零 JSON 解析、零 live 查询。
 * 本类为动态 jar 单例（DynamicJarLoader 只注册 @Service/@RestController，用 @Component 会 NoSuchBeanDefinition），
 * 且方法签名不引用任何 composer 类型（Spring 内省在 ruoyi 类加载器下解析签名会 CNFE）。</p>
 *
 * @author coffee
 */
@Service
public class QualityControlSdkImpl implements QualityControlSdk {

    private static final Logger log = LoggerFactory.getLogger(QualityControlSdkImpl.class);

    /** instruments JSON 数组串解析用（与 qcm 内部一致用 Jackson）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** queryResults 结果行数上限（防大窗全量拉取）；探测查询取上限+1，超限抛 IAE 提示收窄。 */
    static final int QUERY_RESULT_LIMIT = 500;

    private final QcmExecutionOrchestrator orchestrator;
    private final IQcmRecordService recordService;
    private final QcmRecordMapper recordMapper;
    private final PlanParamValidator validator;
    private final QcmRecordPhaseMapper phaseMapper;
    private final QcmRecordKeyParamMapper keyParamMapper;
    private final QcmRecordPointMapper pointMapper;
    private final IQcmPlanService planService;

    @Autowired
    public QualityControlSdkImpl(QcmExecutionOrchestrator orchestrator,
                                 IQcmRecordService recordService,
                                 QcmRecordMapper recordMapper,
                                 PlanParamValidator validator,
                                 QcmRecordPhaseMapper phaseMapper,
                                 QcmRecordKeyParamMapper keyParamMapper,
                                 QcmRecordPointMapper pointMapper,
                                 IQcmPlanService planService) {
        this.orchestrator = orchestrator;
        this.recordService = recordService;
        this.recordMapper = recordMapper;
        this.validator = validator;
        this.phaseMapper = phaseMapper;
        this.keyParamMapper = keyParamMapper;
        this.pointMapper = pointMapper;
        this.planService = planService;
    }

    @Override
    public SdkTriggerReply trigger(SdkTriggerRequest request) {
        if (request == null) {
            return SdkTriggerReply.rejected(REASON_INVALID_PARAM, "请求不能为空");
        }
        // 来源契约（§6）：operator 及其 name 必填——无来源的触发尝试不可归属（trigger_user NOT NULL
        // 不塞假值），reply 拒绝且不留痕（行内留痕矩阵 §7 边界2，log 兜底可追溯）
        SdkOperator operator = request.getOperator();
        if (operator == null || operator.getName() == null || operator.getName().trim().isEmpty()) {
            log.warn("SDK 触发拒绝（INVALID_PARAM）：operator 及其 name 不能为空，触发尝试未留痕");
            return SdkTriggerReply.rejected(REASON_INVALID_PARAM, "operator 及其 name 不能为空");
        }
        // 校验与可解析性判定先于排队分支：拒绝留痕（行内留痕矩阵 §7）需以「参数完整」决定是否建行
        List<String> errors = validator.validateExecutionParams(toPlanSaveDto(request));
        String displayOperator = displayOperator(operator);
        QcExecutionRequest req = isRequestParseable(request) ? assembleRemoteRequest(request) : null;
        // 排队不支持（FR-03-07）：先于互斥闸判；参数完整时建 FAILED 行留痕
        if (request.isAllowQueue()) {
            return rejectedWithTrace(req, displayOperator, REASON_QUEUE_NOT_SUPPORTED,
                    "SDK 触发不支持排队（allowQueue=true 被拒绝）");
        }
        if (!errors.isEmpty()) {
            return rejectedWithTrace(req, displayOperator, REASON_INVALID_PARAM, String.join("; ", errors));
        }
        try {
            BatchResult result = orchestrator.triggerExecution(req, TriggerSource.REMOTE, displayOperator);
            if (result.getStatus() == BatchResult.Status.ACCEPTED) {
                return SdkTriggerReply.builder()
                        .accepted(true)
                        .batchId(result.getBatchId())
                        .recordIds(result.getRecordIds())
                        .triggerRequestId(result.getTriggerRequestId())
                        .message("质控执行已受理，结果经 queryExecution 轮询获取")
                        .build();
            }
            if (result.getStatus() == BatchResult.Status.REJECTED_EXECUTOR_TYPE_NOT_READY) {
                // multi_zero_check 等 composer 未接线类型：与 BUSY 同构留痕，reason 独立可辨（FR-02-14）
                return SdkTriggerReply.builder()
                        .accepted(false)
                        .batchId(result.getBatchId())
                        .recordIds(result.getRecordIds())
                        .triggerRequestId(result.getTriggerRequestId())
                        .reason(REASON_EXECUTOR_TYPE_NOT_READY)
                        .message("执行器类型未接线，本批次 " + result.getRecordIds().size()
                                + " 条记录已写 FAILED 终态留痕（EXECUTOR_TYPE_NOT_READY）")
                        .build();
            }
            return SdkTriggerReply.builder()
                    .accepted(false)
                    .batchId(result.getBatchId())
                    .recordIds(result.getRecordIds())
                    .triggerRequestId(result.getTriggerRequestId())
                    .reason(REASON_BUSY_CONFLICT)
                    .message("执行器被占用，本批次 " + result.getRecordIds().size()
                            + " 条记录已写 FAILED 终态留痕（EXECUTOR_BUSY_CONFLICT）")
                    .build();
        } catch (IllegalStateException e) {
            // 编排器结果格式化器未注册等「执行器类型未就绪」类硬抛
            return SdkTriggerReply.rejected(REASON_EXECUTOR_TYPE_NOT_READY, e.getMessage());
        }
    }

    @Override
    public SdkStopReply stop(SdkStopRequest request) {
        if (request == null) {
            return rejectedStop(REASON_INVALID_PARAM, "请求不能为空");
        }
        // 来源契约（§6）：operator 及其 name 必填——无来源的停止尝试不可归属（updated_by NOT NULL
        // 不塞假值），reply 拒绝且不落痕（与触发侧同一纪律）
        SdkOperator operator = request.getOperator();
        if (operator == null || operator.getName() == null || operator.getName().trim().isEmpty()) {
            log.warn("SDK 停止拒绝（INVALID_PARAM）：operator 及其 name 不能为空，停止尝试未留痕");
            return rejectedStop(REASON_INVALID_PARAM, "operator 及其 name 不能为空");
        }
        // 寻址恰好一键的判定在编排器（REST/SDK 共用唯一真相），此处只翻译其结果
        String displayOperator = displayOperator(operator);
        StopOutcome outcome = orchestrator.stopExecution(request.getRecordId(), trimToNull(request.getBatchId()),
                trimToNull(request.getTriggerRequestId()), request.isAllRunning(), displayOperator);
        return toStopReply(outcome);
    }

    @Override
    public List<SdkRunningExecution> queryRunning() {
        String batchId = orchestrator.runningBatchId();
        if (batchId == null) {
            return Collections.emptyList();
        }
        List<QcmRecord> rows = recordMapper.selectByBatchId(batchId);
        if (rows == null || rows.isEmpty()) {
            // 运行批次行不存在 = 执行状态自洽性被破坏（运行中行被外部删除），如实硬抛，
            // 不谎报「无运行」——那会让操作者误判设备空闲
            throw new IllegalStateException("运行中批次的质控记录行不存在: batchId=" + batchId);
        }
        List<Long> recordIds = new ArrayList<>();
        List<String> instruments = new ArrayList<>();
        for (QcmRecord row : rows) {
            recordIds.add(row.getId());
            if (row.getParameter() != null) {
                instruments.add(row.getParameter());
            }
        }
        QcmRecord first = rows.get(0);
        return Collections.singletonList(SdkRunningExecution.builder()
                .batchId(first.getBatchId())
                .recordIds(recordIds)
                .triggerRequestId(first.getTriggerRequestId())
                .qcType(first.getQualityControlType())
                .instruments(instruments)
                .startTime(first.getStartTime())
                .triggerSource(TriggerSource.fromTaskTypeCode(first.getTaskType()).name())
                .triggerUser(first.getTriggerUser())
                .build());
    }

    /** 停止结果 → SDK 回执（§5）：status 翻译成 REASON_* 词汇；已定位到批次的场景
     *  （受理/幂等拒绝）按批次读记录行补齐业务上下文，回执不哑停。 */
    private SdkStopReply toStopReply(StopOutcome outcome) {
        StopOutcome.Status status = outcome.getStatus();
        final String reason;
        switch (status) {
            case INITIATED:
                reason = REASON_STOP_INITIATED;
                break;
            case ALREADY_SETTLED:
                reason = REASON_ALREADY_TERMINAL;
                break;
            case NOTHING_RUNNING:
                reason = REASON_NOTHING_RUNNING;
                break;
            case NOT_FOUND:
                reason = REASON_RECORD_NOT_FOUND;
                break;
            case INVALID_PARAM:
                reason = REASON_INVALID_PARAM;
                break;
            default:
                throw new IllegalStateException("未知的停止结果状态: " + status);
        }
        boolean accepted = status == StopOutcome.Status.INITIATED;
        String message = accepted
                ? outcome.getMessage() + "；终态经 queryExecution 轮询获取"
                : outcome.getMessage();
        String batchId = outcome.getBatchId();
        List<Long> recordIds = outcome.getRecordIds();
        String qcType = null;
        List<String> instruments = null;
        Instant startTime = null;
        if (batchId != null) {
            List<QcmRecord> rows = recordMapper.selectByBatchId(batchId);
            if (rows != null && !rows.isEmpty()) {
                // 行读取是回执上下文的唯一事实源；批次行刚被外部清空的极端窗口下，
                // 如实只回已知定位（batchId/recordIds），业务字段留空不编造
                recordIds = new ArrayList<>();
                instruments = new ArrayList<>();
                for (QcmRecord row : rows) {
                    recordIds.add(row.getId());
                    if (row.getParameter() != null) {
                        instruments.add(row.getParameter());
                    }
                }
                qcType = rows.get(0).getQualityControlType();
                startTime = rows.get(0).getStartTime();
            }
        }
        return SdkStopReply.builder()
                .accepted(accepted)
                .reason(reason)
                .message(message)
                .batchId(batchId)
                .recordIds(recordIds)
                .qcType(qcType)
                .instruments(instruments)
                .startTime(startTime)
                .build();
    }

    /** 无批次上下文的停止拒绝回执（请求空/操作者缺失：尚未寻址，无可回带的批次信息）。 */
    private static SdkStopReply rejectedStop(String reason, String message) {
        return SdkStopReply.builder()
                .accepted(false)
                .reason(reason)
                .message(message)
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public SdkBatchState queryExecution(String batchId) {
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        List<QcmRecord> rows = recordMapper.selectByBatchId(batchId);
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("记录不存在: batchId=" + batchId);
        }
        boolean terminal = true;
        List<SdkBatchState.RecordSummary> summaries = new ArrayList<>();
        for (QcmRecord row : rows) {
            ExecutionStatusEnum status = statusOf(row.getExecutionStatus());
            if (!isTerminal(status)) {
                terminal = false;
            }
            summaries.add(SdkBatchState.RecordSummary.builder()
                    .recordId(row.getId())
                    .instrument(row.getParameter())
                    .status(status.getCode().intValue())
                    .statusName(status.getDisplayName())
                    .startTime(row.getStartTime())
                    .endTime(row.getEndTime())
                    .resultEvaluation(row.getResultEvaluation())
                    .build());
        }
        return SdkBatchState.builder()
                .batchId(batchId)
                .terminal(terminal)
                .records(summaries)
                .build();
    }

    @Override
    public SdkRecordDetail getRecordDetail(long recordId) {
        // 兼容视图（E2E 仍消费）：内部委托强类型读路径组装，不再解析 execution_log JSON
        QcmRecord record = recordService.selectQcmRecordById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("记录不存在: id=" + recordId);
        }
        SdkExecutionResult result = assemble(record);
        SdkExecutionResult.Judgement j = result.getJudgement();
        return SdkRecordDetail.builder()
                .recordId(result.getRecordId())
                .batchId(result.getBatchId())
                .planId(result.getPlanId())
                .qcType(result.getQcType())
                .instrument(result.getInstrument())
                .triggerSource(result.getTriggerSource())
                .triggerUser(result.getTriggerUser())
                .startTime(result.getStartTime())
                .endTime(result.getEndTime())
                .executionStatus(result.getExecutionStatus())
                .executionStatusName(result.getExecutionStatusName())
                .failureReason(result.getFailureReason())
                .monitoringData(j != null ? j.getDeviceValue() : null)
                .standardValue(j != null ? j.getStdValue() : null)
                .calculatedValue(j != null ? j.getResultValue() : null)
                .resultEvaluation(result.getResultEvaluation())
                .phaseTimelines(result.getPhaseTimelines())
                .recordSnapshot(parseSnapshot(record.getRecordSnapshot()))
                .build();
    }

    @Override
    public List<SdkExecutionResult> queryResults(ResultFilter filter) {
        if (filter == null || filter.isEmpty()) {
            throw new IllegalArgumentException("queryResults 过滤条件不能全空（至少提供时间窗/类型/仪器/触发源/批次/触发请求之一，防全表扫）");
        }
        String taskTypeCode = null;
        if (filter.getTriggerSource() != null && !filter.getTriggerSource().trim().isEmpty()) {
            taskTypeCode = toTaskTypeCode(filter.getTriggerSource().trim());
        }
        List<QcmRecord> rows = recordMapper.selectByFilter(filter.getBegin(), filter.getEnd(),
                filter.getQcType(), filter.getInstrument(), taskTypeCode,
                filter.getBatchId(), filter.getTriggerRequestId(), QUERY_RESULT_LIMIT + 1);
        if (rows != null && rows.size() > QUERY_RESULT_LIMIT) {
            throw new IllegalArgumentException("queryResults 命中超过上限 " + QUERY_RESULT_LIMIT
                    + " 行，请收窄时间窗或增加过滤条件后重试");
        }
        List<SdkExecutionResult> out = new ArrayList<>();
        if (rows != null) {
            for (QcmRecord row : rows) {
                out.add(assemble(row));
            }
        }
        return out;
    }

    @Override
    public SdkExecutionResult getExecutionResult(long recordId) {
        QcmRecord record = recordService.selectQcmRecordById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("记录不存在: id=" + recordId);
        }
        return assemble(record);
    }

    @Override
    public List<SdkPlanSetting> queryPlans(String statusFilter) {
        boolean filterGiven = statusFilter != null && !statusFilter.trim().isEmpty();
        QcmPlan query = new QcmPlan();
        if (filterGiven) {
            query.setStatus(statusFilter.trim());
        }
        List<QcmPlan> plans = planService.selectList(query);
        List<SdkPlanSetting> out = new ArrayList<>();
        if (plans == null) {
            return out;
        }
        for (QcmPlan plan : plans) {
            // 未给状态过滤时排除 FINISHED（已终结的一次性计划不属「当前设置」）
            if (!filterGiven && "FINISHED".equals(plan.getStatus())) {
                continue;
            }
            SdkPlanSetting setting = toPlanSetting(plan);
            if (setting != null) {
                out.add(setting);
            }
        }
        return out;
    }

    /**
     * QcmPlan → SdkPlanSetting：scheduleConfig 经 {@link ScheduleSpecs} 解析出调度语义，
     * instruments 解析 JSON 数组。调度配置非法的计划返回 null（调用方跳过，不整批失败）。
     */
    private SdkPlanSetting toPlanSetting(QcmPlan plan) {
        ScheduleSpec spec;
        try {
            spec = ScheduleSpecs.fromConfig(plan.getScheduleType(), plan.getScheduleConfig(),
                    plan.getPlanStartTime(), plan.getPlanEndTime());
        } catch (Exception e) {
            log.warn("计划 {} 调度配置解析失败，queryPlans 跳过该计划：{}", plan.getId(), e.getMessage());
            return null;
        }
        return SdkPlanSetting.builder()
                .planId(plan.getId() == null ? 0L : plan.getId())
                .planName(plan.getPlanName())
                .qcType(plan.getQcType())
                .instruments(parseInstruments(plan.getInstruments()))
                .scheduleType(plan.getScheduleType())
                .hour(spec.getHour())
                .minute(spec.getMinute())
                .weekdays(spec.getWeekdays())
                .monthDays(spec.getMonthDays())
                .onceAt(spec.getOnceAt())
                .status(plan.getStatus())
                .enabled("ACTIVE".equals(plan.getStatus()))
                .build();
    }

    /** instruments JSON 数组串 → List&lt;String&gt;；空/非法返回空列表（如实呈现，不伪造）。 */
    private static List<String> parseInstruments(String instrumentsJson) {
        if (instrumentsJson == null || instrumentsJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(instrumentsJson,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 五层组装（§4.0）：事件/结论层取 qcm_record 列，过程/工况层取三子表，判定层取列+point 子表。 */
    private SdkExecutionResult assemble(QcmRecord record) {
        ExecutionStatusEnum status = statusOf(record.getExecutionStatus());

        List<SdkRecordDetail.PhaseTimeline> phases = new ArrayList<>();
        List<QcmRecordPhase> phaseRows = phaseMapper.selectByRecordId(record.getId());
        if (phaseRows != null) {
            for (QcmRecordPhase r : phaseRows) {
                phases.add(SdkRecordDetail.PhaseTimeline.builder()
                        .phaseId(r.getPhaseCode())
                        .phaseName(r.getPhaseName())
                        .estimatedSeconds(r.getEstimatedSeconds() != null
                                ? Long.valueOf(r.getEstimatedSeconds()) : null)
                        .start(r.getStartTime())
                        .end(r.getEndTime())
                        .build());
            }
        }

        List<SdkExecutionResult.KeyParameter> keyParams = new ArrayList<>();
        List<QcmRecordKeyParam> keyRows = keyParamMapper.selectByRecordId(record.getId());
        if (keyRows != null) {
            for (QcmRecordKeyParam r : keyRows) {
                keyParams.add(SdkExecutionResult.KeyParameter.builder()
                        .name(r.getName())
                        .value(r.getValue())
                        .unit(r.getUnit())
                        .refRange(r.getRefRange())
                        .build());
            }
        }

        List<BigDecimal> stdValues = new ArrayList<>();
        List<BigDecimal> deviceValues = new ArrayList<>();
        List<QcmRecordPoint> pointRows = pointMapper.selectByRecordId(record.getId());
        if (pointRows != null) {
            for (QcmRecordPoint r : pointRows) {
                stdValues.add(r.getStdValue());
                deviceValues.add(r.getDeviceValue());
            }
        }

        SdkExecutionResult.Judgement judgement = SdkExecutionResult.Judgement.builder()
                .resultValue(record.getCalculatedValue())
                .stdValue(record.getStandardValue())
                .deviceValue(record.getMonitoringData())
                .checkPassLimit(record.getCheckPassLimit())
                .checkCalibLimit(record.getCheckCalibLimit())
                .pass(record.getIsPass())
                .stdValues(stdValues)
                .deviceValues(deviceValues)
                .slope(record.getSlope())
                .intercept(record.getIntercept())
                .correlation(record.getCorrelation())
                .build();

        return SdkExecutionResult.builder()
                .recordId(record.getId())
                .batchId(record.getBatchId())
                .triggerRequestId(record.getTriggerRequestId())
                .planId(record.getPlanId())
                .qcType(record.getQualityControlType())
                .instrument(record.getParameter())
                .triggerSource(TriggerSource.fromTaskTypeCode(record.getTaskType()).name())
                .triggerUser(record.getTriggerUser())
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .executionStatus(status.getCode().intValue())
                .executionStatusName(status.getDisplayName())
                .failureReason(record.getFailureReason())
                .phaseTimelines(phases)
                .judgement(judgement)
                .keyParameters(keyParams)
                .samplingStartTime(record.getSamplingStartTime())
                .samplingEndTime(record.getSamplingEndTime())
                .instrumentName(record.getInstrumentName())
                .instrumentNo(record.getInstrumentNo())
                .gasSource(record.getGasSource())
                .gasNo(record.getGasNo())
                .gasConcentration(record.getGasConcentration())
                .fullScale(record.getFullScale())
                .flowType(record.getFlowType())
                .flowExecutionRef(record.getFlowExecutionRef())
                .resultEvaluation(record.getResultEvaluation())
                .build();
    }

    /** 触发源名（SCHEDULED/MANUAL/REMOTE）→ qcm_record.task_type 编码；未知名硬抛不猜。 */
    private static String toTaskTypeCode(String triggerSourceName) {
        try {
            return TriggerSource.valueOf(triggerSourceName).getCode();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的触发源: " + triggerSourceName
                    + "（合法值 SCHEDULED/MANUAL/REMOTE）", e);
        }
    }

    /** SDK 请求 → 编排器入参（REMOTE 源，planId=null，快照用请求参数自描述）。 */
    private static QcExecutionRequest assembleRemoteRequest(SdkTriggerRequest request) {
        return QcExecutionRequest.builder()
                .planId(null)
                .qcType(request.getQcType())
                .instruments(request.getInstruments())
                .concentrationPpb(request.getConcentrationPpb())
                .pointPercents(request.getPointPercents())
                .flowRateLpm(request.getFlowRateLpm())
                .durationOverrides(widenIntegralDurations(request.getDurationOverrides()))
                .planSnapshotJson(buildRequestSnapshotJson(request))
                .build();
    }

    /**
     * 拒绝留痕（行内留痕矩阵 §7）：参数完整（req 非空）的拒绝建 N 条 FAILED 终态行，
     * reply 回带批次标识不哑拒；残缺请求（req 为 null）无自然落点，reply 拒绝 + log 兜底
     * （§7 边界1：被拒无 DB 痕，日志是唯一追溯手段）。
     */
    private SdkTriggerReply rejectedWithTrace(QcExecutionRequest req, String displayOperator,
                                              String reason, String message) {
        if (req == null) {
            log.warn("SDK 触发拒绝（{}）：残缺请求（qcType/instruments 不可解析）未留痕——{}", reason, message);
            return SdkTriggerReply.rejected(reason, message);
        }
        BatchResult result = orchestrator.persistRejectedBatch(req, TriggerSource.REMOTE, displayOperator,
                reason, message);
        return SdkTriggerReply.builder()
                .accepted(false)
                .batchId(result.getBatchId())
                .recordIds(result.getRecordIds())
                .triggerRequestId(result.getTriggerRequestId())
                .reason(reason)
                .message(message + "；本批次 " + result.getRecordIds().size()
                        + " 条记录已写 FAILED 终态留痕（" + reason + "）")
                .build();
    }

    /**
     * 存储侧拼平（来源契约 §6）：PLATFORM 且 ip 非空 → {@code name@ip[:port]}，其余形态 → name。
     * PLATFORM 缺 ip 时退化为仅 name（网络边界漏填不伪造对端信息）。
     */
    private static String displayOperator(SdkOperator operator) {
        if (operator.getSourceType() == SdkOperatorSource.PLATFORM
                && operator.getIp() != null && !operator.getIp().trim().isEmpty()) {
            return operator.getPort() != null
                    ? operator.getName() + "@" + operator.getIp() + ":" + operator.getPort()
                    : operator.getName() + "@" + operator.getIp();
        }
        return operator.getName();
    }

    /**
     * 建行前置的参数完整性判定（§7 边界1）：qcType 可解析为质控类型且 instruments 逐个可解析为
     * 仪器参数——qcm_record 五个 NOT NULL 业务列填得出才建留痕行。只判「可解析」不判「合法」：
     * 其余字段非法走留痕，残缺请求走纯 reply 拒绝。
     */
    private static boolean isRequestParseable(SdkTriggerRequest request) {
        if (request.getQcType() == null) {
            return false;
        }
        try {
            QualityControlTypeEnum.valueOf(request.getQcType().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (request.getInstruments() == null || request.getInstruments().isEmpty()) {
            return false;
        }
        for (String instrument : request.getInstruments()) {
            if (instrument == null) {
                return false;
            }
            try {
                ParameterEnum.valueOf(instrument);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }

    /** SDK 请求 → 校验器入参等价物（仅执行参数域字段，调度/有效期属计划保存域不参与）。 */
    private static PlanSaveDto toPlanSaveDto(SdkTriggerRequest request) {
        PlanSaveDto dto = new PlanSaveDto();
        dto.setQcType(request.getQcType());
        dto.setInstruments(request.getInstruments());
        dto.setConcentrationPpb(request.getConcentrationPpb());
        dto.setPointPercents(request.getPointPercents());
        dto.setFlowRateLpm(request.getFlowRateLpm());
        dto.setDurationOverrides(widenIntegralDurations(request.getDurationOverrides()));
        return dto;
    }

    /**
     * durationOverrides 值类型适配：校验器要求整数秒为 Integer，SDK 侧签名是 Number——
     * 无小数部分的 Byte/Short/Integer/Long 归一为 Integer（语义同一），其余原样透传由校验器报错。
     */
    private static Map<String, Object> widenIntegralDurations(Map<String, Number> overrides) {
        if (overrides == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Number> e : overrides.entrySet()) {
            Number v = e.getValue();
            if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
                out.put(e.getKey(), v.intValue());
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /** 无计划的 SDK 直接触发：快照用请求参数自描述（计划删除后记录仍可溯源，FR-04-09 同语义）。 */
    private static String buildRequestSnapshotJson(SdkTriggerRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("source", "REMOTE_SDK");
        snapshot.put("operator", operatorSnapshot(request.getOperator()));
        snapshot.put("qcType", request.getQcType());
        snapshot.put("instruments", request.getInstruments());
        snapshot.put("concentrationPpb", request.getConcentrationPpb());
        snapshot.put("pointPercents", request.getPointPercents());
        snapshot.put("flowRateLpm", request.getFlowRateLpm());
        snapshot.put("durationOverrides", request.getDurationOverrides());
        return JsonUtils.toJsonString(snapshot);
    }

    /** 操作者结构化留痕：形态/标识/对端网络信息原样进快照，不拼平（拼平结果只落 trigger_user 列）。 */
    private static Map<String, Object> operatorSnapshot(SdkOperator operator) {
        Map<String, Object> operatorMap = new LinkedHashMap<>();
        operatorMap.put("sourceType", operator.getSourceType() == null ? null : operator.getSourceType().name());
        operatorMap.put("name", operator.getName());
        operatorMap.put("ip", operator.getIp());
        operatorMap.put("port", operator.getPort());
        return operatorMap;
    }

    private static ExecutionStatusEnum statusOf(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("记录执行状态为空，无法映射状态名");
        }
        for (ExecutionStatusEnum e : ExecutionStatusEnum.values()) {
            if (e.getCode().intValue() == code) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的执行状态编码: " + code);
    }

    /** 终态 = 成功/失败；等待中/执行中/手动中止中均为进行态（STOPPING 由 flow.stop 异步收敛）。 */
    private static boolean isTerminal(ExecutionStatusEnum status) {
        return status == ExecutionStatusEnum.SUCCESS || status == ExecutionStatusEnum.FAILED;
    }

    private static Map<String, Object> parseSnapshot(String recordSnapshot) {
        if (recordSnapshot == null || recordSnapshot.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        return JsonUtils.parseMap(recordSnapshot, String.class, Object.class);
    }
}
