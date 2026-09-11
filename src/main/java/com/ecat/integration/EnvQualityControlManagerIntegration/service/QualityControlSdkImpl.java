package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.api.QualityControlSdk;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.ResultFilter;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkBatchState;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkDurationKey;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkExecutionResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkExecutionStatus;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkFailureReason;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkInstrument;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkOperator;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkOperatorSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkPlanSetting;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkPlanStatus;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkQcType;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkReason;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkRecordDetail;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkRunningExecution;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkScheduleType;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkStopReply;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkStopRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerReply;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerSource;
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
 * 闭域词汇（§3 九域）在本类单向收口：api 枚举 ↔ 内部存储形态（仪器/质控类型落码、
 * 质控类型计划侧落名、触发源落任务类型编码、时长键 camelCase），编排器/mapper/DB 零改动。
 * 本类为动态 jar 单例（DynamicJarLoader 只注册 @Service/@RestController，用 @Component 会 NoSuchBeanDefinition），
 * 且方法签名只引用 api 包类型（Spring 内省在 ruoyi 类加载器下解析 composer/内部签名会 CNFE）。</p>
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
            return SdkTriggerReply.rejected(SdkReason.INVALID_PARAM, "请求不能为空");
        }
        // 来源契约（§6）：operator 及其 name 必填——无来源的触发尝试不可归属（trigger_user NOT NULL
        // 不塞假值），reply 拒绝且不留痕（行内留痕矩阵 §7 边界2，log 兜底可追溯）
        SdkOperator operator = request.getOperator();
        if (operator == null || operator.getName() == null || operator.getName().trim().isEmpty()) {
            log.warn("SDK 触发拒绝（INVALID_PARAM）：operator 及其 name 不能为空，触发尝试未留痕");
            return SdkTriggerReply.rejected(SdkReason.INVALID_PARAM, "operator 及其 name 不能为空");
        }
        // 校验与可解析性判定先于排队分支：拒绝留痕（行内留痕矩阵 §7）需以「参数完整」决定是否建行
        List<String> errors = validator.validateExecutionParams(toPlanSaveDto(request));
        String displayOperator = displayOperator(operator);
        QcExecutionRequest req = isRequestParseable(request) ? assembleRemoteRequest(request) : null;
        // 排队不支持（FR-03-07）：先于互斥闸判；参数完整时建 FAILED 行留痕
        if (request.isAllowQueue()) {
            return rejectedWithTrace(req, displayOperator, SdkReason.QUEUE_NOT_SUPPORTED,
                    "SDK 触发不支持排队（allowQueue=true 被拒绝）");
        }
        if (!errors.isEmpty()) {
            return rejectedWithTrace(req, displayOperator, SdkReason.INVALID_PARAM, String.join("; ", errors));
        }
        try {
            BatchResult result = orchestrator.triggerExecution(req, TriggerSource.REMOTE, displayOperator);
            if (result.getStatus() == BatchResult.Status.ACCEPTED) {
                return SdkTriggerReply.builder()
                        .accepted(true)
                        .batchId(result.getBatchId())
                        .recordIds(result.getRecordIds())
                        .triggerRequestId(result.getTriggerRequestId())
                        .reason(SdkReason.ACCEPTED)
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
                        .reason(SdkReason.EXECUTOR_TYPE_NOT_READY)
                        .message("执行器类型未接线，本批次 " + result.getRecordIds().size()
                                + " 条记录已写 FAILED 终态留痕（EXECUTOR_TYPE_NOT_READY）")
                        .build();
            }
            return SdkTriggerReply.builder()
                    .accepted(false)
                    .batchId(result.getBatchId())
                    .recordIds(result.getRecordIds())
                    .triggerRequestId(result.getTriggerRequestId())
                    .reason(SdkReason.BUSY_CONFLICT)
                    .message("执行器被占用，本批次 " + result.getRecordIds().size()
                            + " 条记录已写 FAILED 终态留痕（EXECUTOR_BUSY_CONFLICT）")
                    .build();
        } catch (IllegalStateException e) {
            // 编排器结果格式化器未注册等「执行器类型未就绪」类硬抛
            return SdkTriggerReply.rejected(SdkReason.EXECUTOR_TYPE_NOT_READY, e.getMessage());
        }
    }

    @Override
    public SdkStopReply stop(SdkStopRequest request) {
        if (request == null) {
            return rejectedStop(SdkReason.INVALID_PARAM, "请求不能为空");
        }
        // 来源契约（§6）：operator 及其 name 必填——无来源的停止尝试不可归属（updated_by NOT NULL
        // 不塞假值），reply 拒绝且不落痕（与触发侧同一纪律）
        SdkOperator operator = request.getOperator();
        if (operator == null || operator.getName() == null || operator.getName().trim().isEmpty()) {
            log.warn("SDK 停止拒绝（INVALID_PARAM）：operator 及其 name 不能为空，停止尝试未留痕");
            return rejectedStop(SdkReason.INVALID_PARAM, "operator 及其 name 不能为空");
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
        List<SdkInstrument> instruments = new ArrayList<>();
        for (QcmRecord row : rows) {
            recordIds.add(row.getId());
            if (row.getParameter() != null) {
                instruments.add(instrumentOfCode(row.getParameter()));
            }
        }
        QcmRecord first = rows.get(0);
        return Collections.singletonList(SdkRunningExecution.builder()
                .batchId(first.getBatchId())
                .recordIds(recordIds)
                .triggerRequestId(first.getTriggerRequestId())
                .qcType(qcTypeOfRecordValue(first.getQualityControlType()))
                .instruments(instruments)
                .startTime(first.getStartTime())
                .triggerSource(sdkTriggerSource(TriggerSource.fromTaskTypeCode(first.getTaskType())))
                .triggerUser(first.getTriggerUser())
                .build());
    }

    /** 停止结果 → SDK 回执（§5）：status 翻译成受理/拒绝原因词汇；已定位到批次的场景
     *  （受理/幂等拒绝）按批次读记录行补齐业务上下文，回执不哑停。 */
    private SdkStopReply toStopReply(StopOutcome outcome) {
        StopOutcome.Status status = outcome.getStatus();
        final SdkReason reason;
        switch (status) {
            case INITIATED:
                reason = SdkReason.STOP_INITIATED;
                break;
            case ALREADY_SETTLED:
                reason = SdkReason.ALREADY_TERMINAL;
                break;
            case NOTHING_RUNNING:
                reason = SdkReason.NOTHING_RUNNING;
                break;
            case NOT_FOUND:
                reason = SdkReason.RECORD_NOT_FOUND;
                break;
            case INVALID_PARAM:
                reason = SdkReason.INVALID_PARAM;
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
        SdkQcType qcType = null;
        List<SdkInstrument> instruments = null;
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
                        instruments.add(instrumentOfCode(row.getParameter()));
                    }
                }
                qcType = qcTypeOfRecordValue(rows.get(0).getQualityControlType());
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
    private static SdkStopReply rejectedStop(SdkReason reason, String message) {
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
                    .instrument(instrumentOfCode(row.getParameter()))
                    .status(status.getCode().intValue())
                    .statusName(executionStatus(status))
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
        // 过滤条件换算成存储词汇再下 SQL：仪器传枚举直译存储码（旧契约传字母名查空的陷阱在边界消除）
        String taskTypeCode = filter.getTriggerSource() == null
                ? null : internalTriggerSource(filter.getTriggerSource()).getCode();
        List<QcmRecord> rows = recordMapper.selectByFilter(filter.getBegin(), filter.getEnd(),
                filter.getQcType() == null ? null : qcTypeCode(filter.getQcType()),
                filter.getInstrument() == null ? null : instrumentCode(filter.getInstrument()),
                taskTypeCode,
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
    public List<SdkPlanSetting> queryPlans(SdkPlanStatus statusFilter) {
        boolean filterGiven = statusFilter != null;
        QcmPlan query = new QcmPlan();
        if (filterGiven) {
            query.setStatus(statusFilter.name());
        }
        List<QcmPlan> plans = planService.selectList(query);
        List<SdkPlanSetting> out = new ArrayList<>();
        if (plans == null) {
            return out;
        }
        for (QcmPlan plan : plans) {
            // 未给状态过滤时排除 FINISHED（已终结的一次性计划不属「当前设置」）
            if (!filterGiven && SdkPlanStatus.FINISHED.name().equals(plan.getStatus())) {
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
        SdkScheduleType scheduleType;
        try {
            spec = ScheduleSpecs.fromConfig(plan.getScheduleType(), plan.getScheduleConfig(),
                    plan.getPlanStartTime(), plan.getPlanEndTime());
            scheduleType = SdkScheduleType.valueOf(spec.getType().name());
        } catch (Exception e) {
            log.warn("计划 {} 调度配置解析失败，queryPlans 跳过该计划：{}", plan.getId(), e.getMessage());
            return null;
        }
        SdkPlanStatus status = SdkPlanStatus.valueOf(plan.getStatus());
        return SdkPlanSetting.builder()
                .planId(plan.getId() == null ? 0L : plan.getId())
                .planName(plan.getPlanName())
                .qcType(qcTypeOfName(plan.getQcType()))
                .instruments(instrumentsOfNameJson(plan.getInstruments()))
                .scheduleType(scheduleType)
                .hour(spec.getHour())
                .minute(spec.getMinute())
                .weekdays(spec.getWeekdays())
                .monthDays(spec.getMonthDays())
                .onceAt(spec.getOnceAt())
                .status(status)
                .enabled(status == SdkPlanStatus.ACTIVE)
                .build();
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
                .qcType(qcTypeOfRecordValue(record.getQualityControlType()))
                .instrument(instrumentOfCode(record.getParameter()))
                .triggerSource(sdkTriggerSource(TriggerSource.fromTaskTypeCode(record.getTaskType())))
                .triggerUser(record.getTriggerUser())
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .executionStatus(status.getCode().intValue())
                .executionStatusName(executionStatus(status))
                .failureReason(failureReasonOf(record.getFailureReason()))
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

    /** instruments JSON 数组串 → api 仪器列表；空/非法返回空列表（如实呈现，不伪造）。 */
    private static List<SdkInstrument> instrumentsOfNameJson(String instrumentsJson) {
        if (instrumentsJson == null || instrumentsJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> names;
        try {
            names = MAPPER.readValue(instrumentsJson,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
        return instrumentsOfNames(names);
    }

    // ===== 闭域词汇翻译（§3 九域，api 枚举 ↔ 内部存储形态，全模块唯一收口点） =====

    /** api 仪器 → 内部字母名（ParameterEnum name，逻辑设备入口词汇）。 */
    private static String instrumentName(SdkInstrument instrument) {
        return ParameterEnum.valueOf(instrument.name()).getName();
    }

    /** api 仪器 → 内部存储码（qcm_record.parameter / SQL 过滤词汇）。 */
    private static String instrumentCode(SdkInstrument instrument) {
        return ParameterEnum.valueOf(instrument.name()).getCode();
    }

    /** 存储码 → api 仪器。按常量名反查而非展示名：PM2_5 展示名带点（PM2.5）不能作枚举名。
     *  译不出抛 IAE 带原码（禁伪造，R4）。 */
    private static SdkInstrument instrumentOfCode(String code) {
        for (ParameterEnum p : ParameterEnum.values()) {
            if (p.getCode().equals(code)) {
                return SdkInstrument.valueOf(p.name());
            }
        }
        throw new IllegalArgumentException("未知的仪器存储码: " + code);
    }

    /** 内部字母名列表 → api 仪器列表（qcm_plan.instruments JSON 词汇）；null 元素原样保留由校验侧报错。 */
    private static List<SdkInstrument> instrumentsOfNames(List<String> names) {
        List<SdkInstrument> out = new ArrayList<>();
        for (String name : names) {
            out.add(name == null ? null : instrumentOfName(name));
        }
        return out;
    }

    /** 内部字母名 → api 仪器（按展示名反查）；译不出抛 IAE 带原值（R4）。 */
    private static SdkInstrument instrumentOfName(String name) {
        for (ParameterEnum p : ParameterEnum.values()) {
            if (p.getName().equals(name)) {
                return SdkInstrument.valueOf(p.name());
            }
        }
        throw new IllegalArgumentException("未知的仪器: " + name);
    }

    /** api 仪器列表 → 内部字母名列表；null 列表原样返回，null 元素原样保留（残缺请求不伪造）。 */
    private static List<String> instrumentNames(List<SdkInstrument> instruments) {
        if (instruments == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (SdkInstrument instrument : instruments) {
            out.add(instrument == null ? null : instrumentName(instrument));
        }
        return out;
    }

    /** api 质控类型 → 内部 snake_case name（计划/请求域词汇）。 */
    private static String qcTypeName(SdkQcType qcType) {
        return QualityControlTypeEnum.valueOf(qcType.name()).getName();
    }

    /** api 质控类型 → 记录侧存储码（qcm_record.quality_control_type / SQL 过滤词汇）。
     *  与 {@link #qcTypeName} 必须分作两条：质控类型是双路径词汇（R7）——记录列落数字码
     *  （编排器 buildRecords 落 {@code qcEnum.getCode()}），计划/请求域落 snake_case name；
     *  记录侧过滤用 name 会恒查空（真库符合性 IT 实证：'span_check' 命中 0 行、码命中 108 行）。 */
    private static String qcTypeCode(SdkQcType qcType) {
        return QualityControlTypeEnum.valueOf(qcType.name()).getCode();
    }

    /** 计划侧 snake_case name → api 质控类型；译不出抛 IAE 带原值（保存时已校验，出现即缺陷）。 */
    private static SdkQcType qcTypeOfName(String name) {
        for (QualityControlTypeEnum e : QualityControlTypeEnum.values()) {
            if (e.getName().equals(name)) {
                return SdkQcType.valueOf(e.name());
            }
        }
        throw new IllegalArgumentException("未知的质控类型: " + name);
    }

    /** 记录侧数字码 → api 质控类型。历史遗留行（G-STD-4 的 calibration_check）不在闭域词汇内，
     *  如实回 null 而非整查询失败——它是代码明确证明的合法边界；其余译不出抛 IAE 带原值（R4）。 */
    private static SdkQcType qcTypeOfRecordValue(String stored) {
        if (QualityControlTypeEnum.LEGACY_CALIBRATION_CHECK_TYPE.equals(stored)) {
            return null;
        }
        QualityControlTypeEnum e = QualityControlTypeEnum.fromCode(stored);
        if (e == null) {
            throw new IllegalArgumentException("未知的质控类型存储值: " + stored);
        }
        return SdkQcType.valueOf(e.name());
    }

    /** api 触发源 → 内部触发源（同名语义直映射）。 */
    private static TriggerSource internalTriggerSource(SdkTriggerSource source) {
        return TriggerSource.valueOf(source.name());
    }

    /** 内部触发源 → api 触发源（同名语义直映射）。 */
    private static SdkTriggerSource sdkTriggerSource(TriggerSource source) {
        return SdkTriggerSource.valueOf(source.name());
    }

    /** 内部执行状态 → api 执行状态（同名语义直映射，中文名串不再出契约）。 */
    private static SdkExecutionStatus executionStatus(ExecutionStatusEnum status) {
        return SdkExecutionStatus.valueOf(status.name());
    }

    /** failure_reason 落库值 → api 失败原因；null=无结构化原因（如实透传）；译不出抛 IAE 带原值。 */
    private static SdkFailureReason failureReasonOf(String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return null;
        }
        try {
            return SdkFailureReason.valueOf(stored);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的失败原因存储值: " + stored, e);
        }
    }

    /** api 时长覆盖 → 内部 flowParams 键值对：键换 camelCase 白名单键，整数型值归一为 Integer（校验器口径）。 */
    private static Map<String, Object> durationOverrides(Map<SdkDurationKey, Number> overrides) {
        if (overrides == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<SdkDurationKey, Number> e : overrides.entrySet()) {
            out.put(e.getKey().getKey(), widenIntegral(e.getValue()));
        }
        return out;
    }

    /**
     * 时长值类型适配：校验器要求整数秒为 Integer，SDK 侧签名是 Number——
     * 无小数部分的 Byte/Short/Integer/Long 归一为 Integer（语义同一），其余原样透传由校验器报错。
     */
    private static Object widenIntegral(Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value.intValue();
        }
        return value;
    }

    /** SDK 请求 → 编排器入参（REMOTE 源，planId=null，快照用请求参数自描述，词汇与计划侧快照同形态）。 */
    private static QcExecutionRequest assembleRemoteRequest(SdkTriggerRequest request) {
        return QcExecutionRequest.builder()
                .planId(null)
                .qcType(qcTypeName(request.getQcType()))
                .instruments(instrumentNames(request.getInstruments()))
                .concentrationPpb(request.getConcentrationPpb())
                .pointPercents(request.getPointPercents())
                .flowRateLpm(request.getFlowRateLpm())
                .durationOverrides(durationOverrides(request.getDurationOverrides()))
                .planSnapshotJson(buildRequestSnapshotJson(request))
                .build();
    }

    /**
     * 拒绝留痕（行内留痕矩阵 §7）：参数完整（req 非空）的拒绝建 N 条 FAILED 终态行，
     * reply 回带批次标识不哑拒；残缺请求（req 为 null）无自然落点，reply 拒绝 + log 兜底
     * （§7 边界1：被拒无 DB 痕，日志是唯一追溯手段）。
     */
    private SdkTriggerReply rejectedWithTrace(QcExecutionRequest req, String displayOperator,
                                              SdkReason reason, String message) {
        if (req == null) {
            log.warn("SDK 触发拒绝（{}）：残缺请求（qcType/instruments 缺失）未留痕——{}", reason, message);
            return SdkTriggerReply.rejected(reason, message);
        }
        BatchResult result = orchestrator.persistRejectedBatch(req, TriggerSource.REMOTE, displayOperator,
                reason.name(), message);
        return SdkTriggerReply.builder()
                .accepted(false)
                .batchId(result.getBatchId())
                .recordIds(result.getRecordIds())
                .triggerRequestId(result.getTriggerRequestId())
                .reason(reason)
                .message(message + "；本批次 " + result.getRecordIds().size()
                        + " 条记录已写 FAILED 终态留痕（" + reason.name() + "）")
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
     * 建行前置的参数完整性判定（§7 边界1）：质控类型/仪器已由 api 枚举在编译期闭域，
     * 剩余的「残缺」只有字段缺失——qcm_record 五个 NOT NULL 业务列填得出才建留痕行。
     * 只判「填得出」不判「合法」：其余字段非法走留痕，残缺请求走纯 reply 拒绝。
     */
    private static boolean isRequestParseable(SdkTriggerRequest request) {
        if (request.getQcType() == null) {
            return false;
        }
        if (request.getInstruments() == null || request.getInstruments().isEmpty()) {
            return false;
        }
        for (SdkInstrument instrument : request.getInstruments()) {
            if (instrument == null) {
                return false;
            }
        }
        return true;
    }

    /** SDK 请求 → 校验器入参等价物（仅执行参数域字段，词汇已换算为校验器存储形态）。 */
    private static PlanSaveDto toPlanSaveDto(SdkTriggerRequest request) {
        PlanSaveDto dto = new PlanSaveDto();
        dto.setQcType(request.getQcType() == null ? null : qcTypeName(request.getQcType()));
        dto.setInstruments(instrumentNames(request.getInstruments()));
        dto.setConcentrationPpb(request.getConcentrationPpb());
        dto.setPointPercents(request.getPointPercents());
        dto.setFlowRateLpm(request.getFlowRateLpm());
        dto.setDurationOverrides(durationOverrides(request.getDurationOverrides()));
        return dto;
    }

    /** 无计划的 SDK 直接触发：快照用请求参数自描述（计划删除后记录仍可溯源，FR-04-09 同语义）。
     *  快照落内部存储词汇（与计划触发侧快照同形态），api 枚举不出现在存储面。 */
    private static String buildRequestSnapshotJson(SdkTriggerRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("source", "REMOTE_SDK");
        snapshot.put("operator", operatorSnapshot(request.getOperator()));
        snapshot.put("qcType", request.getQcType() == null ? null : qcTypeName(request.getQcType()));
        snapshot.put("instruments", instrumentNames(request.getInstruments()));
        snapshot.put("concentrationPpb", request.getConcentrationPpb());
        snapshot.put("pointPercents", request.getPointPercents());
        snapshot.put("flowRateLpm", request.getFlowRateLpm());
        snapshot.put("durationOverrides", durationOverrides(request.getDurationOverrides()));
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
