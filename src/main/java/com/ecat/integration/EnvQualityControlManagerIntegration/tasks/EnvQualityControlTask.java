package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Task.Task;
import com.ecat.core.Utils.DynamicConfig.*;
import com.ecat.integration.EnvCalibrationComposerIntegration.*;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;
import com.ruoyi.common.utils.DateUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.TaskTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class EnvQualityControlTask extends Task {
    private DeviceRegistry deviceRegistry;

    private IEnvQualityControlRecordsService envQualityControlRecordsService;
    private EcatCoreRuoyiIntegration mry;

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    public static final Map<String, String> parameterMap = new HashMap<>();

    private Map<Long, AbstractCalibrationFlow> executorMap;

    public EnvQualityControlTask(Map<Long, AbstractCalibrationFlow> executorMap){
        this.executorMap = executorMap;
    }
    @Override
    public String getTaskName() {
        return "EnvQualityControlTask";
    }

    @Override
    public String getDescription() {
        return "质控任务";
    }

    @Override
    protected ConfigDefinition getConfigDefinition() {
        // 定义配置项

        Set<String> validTriggerTypekValues = new HashSet<>(Arrays.asList("0", "1", "2", "3"));
        Set<String> validTaskTypeValues = TaskTypeEnum.getAllTaskTypeCodes();
        Set<String> validParameterValues = ParameterEnum.getAllParameterNameSet();
        Set<String> validQualityControlTypeValues = QualityControlTypeEnum.getAllQualityControlTypeNameSet();

        ConfigDefinition configDefinition = new ConfigDefinition();
        ConfigItemBuilder builder = new ConfigItemBuilder()
                .add(new ConfigItem<>("triggerType", String.class, false, null, new StringEnumValidator(validTriggerTypekValues)))
                .add(new ConfigItem<>("taskType", String.class, false, null, new StringEnumValidator(validTaskTypeValues)))
                .add(new ConfigItem<>("qualityControlType", String.class, true, null, new StringEnumValidator(validQualityControlTypeValues)))
                .add(new ConfigItem<>("parameter", String.class, true, null, new StringEnumValidator(validParameterValues)))
                .add(new ConfigItem<>("deviceId", String.class, false, null, new StringLengthValidator(1, 50)));

//                .add(new ConfigItem<>("calculatedValue", double.class, true, null, new DobleValueValidator(0, 50)));
        configDefinition.define(builder);
        return configDefinition;
    }

    /**
     * 构造结果
     * @param params 入参
     * @param result 执行结果
     * @param envQualityControlTypeCode 质控类型code
     * @param qcRecordId 小于 0 时不写入关键参数快照（失败路径等）；否则在任务完成时刻抓取当前分析仪关键参数（编排器后续写入 {@link QualityControlExecutionLogHelper#QC_PHASE_TIMELINES_KEY} 时，报告侧优先按读数相位时间窗回退）。
     * @return 记录质控最终入库的参数+结果
     */
    protected String constructResult(EcatCore core, Map<String, Object> params, ExecutorResultBase result,
                                       String envQualityControlTypeCode, long qcRecordId) {

        Map<String, Object> serializableParams = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                Object v = e.getValue();
                if (QualityControlExecutionLogHelper.QC_PHASE_TIMELINES_KEY.equals(e.getKey()) && v instanceof List) {
                    serializableParams.put(e.getKey(), new ArrayList<>((List<?>) v));
                    continue;
                }
                if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
                    serializableParams.put(e.getKey(), v);
                }
            }
        }

        Map<String, Object> metrics = new LinkedHashMap<>();

        if (envQualityControlTypeCode.equals(QualityControlTypeEnum.ZERO_CHECK.getCode())) {
            CheckResult checkResult = (CheckResult) result;
            float resultValue = checkResult.getResult();
            float stdValue = checkResult.getStdValue();
            float deviceValue = checkResult.getDeviceValue();
            float checkPassLimit = checkResult.getCheckPassLimit();
            float checkCalibLimit = checkResult.getCheckCalibLimit();
            metrics.put("resultValue", resultValue);
            metrics.put("stdValue", stdValue);
            metrics.put("deviceValue", deviceValue);
            metrics.put("checkPassLimit", checkPassLimit);
            metrics.put("checkCalibLimit", checkCalibLimit);
            metrics.put("isPass", result.isPass());

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.SPAN_CHECK.getCode())) {
            CheckResult checkResult = (CheckResult) result;
            float resultValue = checkResult.getResult();
            float stdValue = checkResult.getStdValue();
            float deviceValue = checkResult.getDeviceValue();
            float checkPassLimit = checkResult.getCheckPassLimit();
            float checkCalibLimit = checkResult.getCheckCalibLimit();
            metrics.put("resultValue", resultValue);
            metrics.put("stdValue", stdValue);
            metrics.put("deviceValue", deviceValue);
            metrics.put("checkPassLimit", checkPassLimit);
            metrics.put("checkCalibLimit", checkCalibLimit);
            metrics.put("isPass", result.isPass());

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.MULTI_CHECK.getCode())) {
            MultiResult multiResult = (MultiResult) result;
            float slope = multiResult.getSlope();
            float intercept = multiResult.getIntercept();
            float correlation = multiResult.getCorrelation();
            List<Float> deviceValues = multiResult.getDeviceValues();
            List<Float> stdValues = multiResult.getStdValues();
            float check_r_min = multiResult.getCheck_r_min();
            float check_a_max = multiResult.getCheck_a_max();
            float check_a_min = multiResult.getCheck_a_min();
            float check_b_scope = multiResult.getCheck_b_scope();
            metrics.put("slope", slope);
            metrics.put("intercept", intercept);
            metrics.put("correlation", correlation);
            metrics.put("deviceValues", deviceValues);
            metrics.put("stdValues", stdValues);
            metrics.put("check_r_min", check_r_min);
            metrics.put("check_a_max", check_a_max);
            metrics.put("check_a_min", check_a_min);
            metrics.put("check_b_scope", check_b_scope);
            metrics.put("isPass", result.isPass());

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.PRECISION_CHECK.getCode())) {
            PrecisionResult precisionResult = (PrecisionResult) result;
            float precision = precisionResult.getPrecision();
            float mean = precisionResult.getMean();
            float standardDeviation = precisionResult.getStandardDeviation();
            float checkRsd20Max = precisionResult.getCheckRsd20Max();
            float deviceStdGas = precisionResult.getDeviceStdGas();
            List<Float> deviceValues = precisionResult.getDeviceValues();
            metrics.put("precision", precision);
            metrics.put("mean", mean);
            metrics.put("standardDeviation", standardDeviation);
            metrics.put("deviceStdGas", deviceStdGas);
            metrics.put("devicesStdGas", deviceStdGas);
            metrics.put("checkRsd20Max", checkRsd20Max);
            metrics.put("deviceValues", deviceValues);
            metrics.put("isPass", result.isPass());

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.ACCURACY_CHECK.getCode())) {
            AccuracyResult accuracyResult = (AccuracyResult) result;
            float slope = accuracyResult.getSlope();
            float intercept = accuracyResult.getIntercept();
            float correlation = accuracyResult.getCorrelation();
            List<Float> deviceValues = accuracyResult.getDeviceValues();
            List<Float> stdValues = accuracyResult.getStdValues();
            float check_r_min = accuracyResult.getCheck_r_min();
            float check_a_max = accuracyResult.getCheck_a_max();
            float check_a_min = accuracyResult.getCheck_a_min();
            float check_b_scope = accuracyResult.getCheck_b_scope();
            metrics.put("slope", slope);
            metrics.put("intercept", intercept);
            metrics.put("correlation", correlation);
            metrics.put("deviceValues", deviceValues);
            metrics.put("stdValues", stdValues);
            metrics.put("check_r_min", check_r_min);
            metrics.put("check_a_max", check_a_max);
            metrics.put("check_a_min", check_a_min);
            metrics.put("check_b_scope", check_b_scope);
            metrics.put("relativeError", accuracyResult.getRelativeError());
            metrics.put("isPass", result.isPass());

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.CONVERSION_CHECK.getCode())) {
            ConversionResult conversionResult = (ConversionResult) result;
            float efficiency = conversionResult.getEfficiency();
            List<Float> origNoDatas = conversionResult.getOrigNoDatas();
            List<Float> origNoxDatas = conversionResult.getOrigNoxDatas();
            List<Float> remNoDatas = conversionResult.getRemNoDatas();
            List<Float> remNoxDatas = conversionResult.getRemNoxDatas();
            Float origNoAvg = conversionResult.getOrigNoAvg();
            Float origNoxAvg = conversionResult.getOrigNoxAvg();
            Float remNoAvg = conversionResult.getRemNoAvg();
            Float remNoxAvg = conversionResult.getRemNoxAvg();
            metrics.put("efficiency", efficiency);
            metrics.put("origNoDatas", origNoDatas);
            metrics.put("origNoxDatas", origNoxDatas);
            metrics.put("origNoAvg", origNoAvg);
            metrics.put("origNoxAvg", origNoxAvg);
            metrics.put("remNoDatas", remNoDatas);
            metrics.put("remNoAvg", remNoAvg);
            metrics.put("remNoxDatas", remNoxDatas);
            metrics.put("remNoxAvg", remNoxAvg);
            metrics.put("isPass", result.isPass());

        } else {
            throw new UnsupportedOperationException("暂不支持该类型质控");
        }

        List<Map<String, Object>> keySnapshot;
        if (qcRecordId < 0) {
            keySnapshot = Collections.emptyList();
        } else {
            String paramStr = params != null ? (String) params.get("parameter") : null;
            String gasName = paramStr != null ? ParameterEnum.valueOf(paramStr).name() : null;
            keySnapshot = buildKeyParametersSnapshotAtComplete(core, gasName);
        }
        return QualityControlExecutionLogHelper.toExecutionLogJson(serializableParams, metrics, result, keySnapshot);
    }

    private List<Map<String, Object>> buildKeyParametersSnapshotAtComplete(EcatCore core, String gasParameterName) {
        if (core == null || gasParameterName == null || gasParameterName.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> rows = LogicDeviceReportSupport.buildAnalyzerKeyParameters(core, gasParameterName);
            return rows != null ? rows : Collections.emptyList();
        } catch (Exception e) {
            log.debug("keyParametersSnapshot skipped: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    protected void executeImpl(Map<String, Object> parameters) {

        try {
            // 设置属性
            String taskType = (String) parameters.get("taskType");
            // String deviceId= (String) parameters.get("deviceId");
            String parameter = (String) parameters.get("parameter");
            String qualityControlType = (String) parameters.get("qualityControlType");
            // double calculatedValue= (double) parameters.get("calculatedValue");
            EcatCore core = (EcatCore) parameters.remove("core");  // 弹出core以便入库存储

            // （提前）组装质控记录数据
            // 增加记录
            EnvQualityControlRecords envQualityControlRecords = new EnvQualityControlRecords();
            envQualityControlRecords.setTaskType(taskType);
            envQualityControlRecords.setQualityControlType(QualityControlTypeEnum.valueOf(qualityControlType.toUpperCase()).getCode());
            envQualityControlRecords.setParameter(ParameterEnum.valueOf(parameter).getCode());
            envQualityControlRecords.setStartTime(DateUtils.getNowDate());
            envQualityControlRecords.setCreatedBy("admin"); // 临时
            envQualityControlRecords.setUpdatedBy("admin");
            envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.WAITING.getCode());  // 执行状态：0=等待中，1=运行中，2=成功结束，3=异常结束
            // envQualityControlRecords.setCalculatedValue(calculatedValue);
            if (mry == null) {
                mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry().getIntegration("integration-ecat-core-ruoyi");
            }
            envQualityControlRecordsService = mry.getSpringBean(IEnvQualityControlRecordsService.class);

            // Insert a record，recordId will be generated
            int insertCount =envQualityControlRecordsService.insertEnvQualityControlRecords(envQualityControlRecords);
            if (insertCount == 1) {
                log.info("insert record success.");
            } else {
                log.error("insert record failed.");
                throw new RuntimeException("insert records failed.");
            }

            // env-calibration-composer：LogicDevice 编排校准，替代 env-device-calibration
            EnvCalibrationComposerIntegration integration = (EnvCalibrationComposerIntegration) core.getIntegrationRegistry().getIntegration("integration-env-calibration-composer");

            // Check if any calibration task is running
            if (Boolean.TRUE.equals(integration.isRunning())) {
                log.error("Calibration task exit: Other calibration task is currently running.");
                String message = "校准任务退出 已有执行中的校准任务";
                envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                envQualityControlRecords.setExecutionLog(message);  // 执行日志
                envQualityControlRecords.setResultEvaluation(message);  // 结果评价
                envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
                envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
                throw new RuntimeException(message);
            } else {
                log.info("Calibration task start, no other calibration task is running at the moment.");
                String message = "校准任务执行中...";
                envQualityControlRecords.setExecutionLog(message);
                envQualityControlRecords.setResultEvaluation(message);
                envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.RUNNING.getCode());
                envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
            }

            // Execute a specific calibration task
            String executerClass = QualityControlTypeEnum.valueOf(qualityControlType.toUpperCase()).getClassName();
            ExecutorType execType = ExecutorType.getEnum(executerClass);
            String gasForComposer = LogicDeviceBindingIds.composerGasKeyFromParameterName(parameter);

            Number flowRateNum = (Number) parameters.get("targetFlowLpm");
            String qcCode = QualityControlTypeEnum.valueOf(qualityControlType.toUpperCase()).getCode();
            boolean zeroOrSpan = QualityControlTypeEnum.ZERO_CHECK.getCode().equals(qcCode)
                    || QualityControlTypeEnum.SPAN_CHECK.getCode().equals(qcCode);

            CompletableFuture<ExecutorResultBase> calibrationFuture;
            if (zeroOrSpan) {
                Map<String, Object> flowParams = new HashMap<>();
                if (flowRateNum != null) {
                    flowParams.put("flowRateLpm", flowRateNum.floatValue());
                }
                float spanPpb;
                if (QualityControlTypeEnum.ZERO_CHECK.getCode().equals(qcCode)) {
                    spanPpb = 0f;
                } else {
                    spanPpb = "CO".equalsIgnoreCase(parameter) ? 40000f : 400f;
                }
                flowParams.put("spanConcentrationPpb", spanPpb);
                calibrationFuture = integration.execute(execType, gasForComposer, flowParams);
            } else {
                calibrationFuture = integration.execute(execType, gasForComposer);
            }
            AbstractCalibrationFlow executor = integration.getRunningExecutor();
            executorMap.put(envQualityControlRecords.getId(), executor);
            if (executor == null) {
                log.error("Calibration task failed: No calibration task executor found.");
                String message = "校准任务未执行 " + "没有可用的执行器";
                envQualityControlRecords.setExecutionLog(message);
                envQualityControlRecords.setResultEvaluation(message);
                envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
                envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
                throw new RuntimeException(message);
            }
            calibrationFuture.thenAccept(result -> {
                /**
                 * 获取执行结果
                 * 以单点为例：
                 * CheckResult result
                 *       "isPass": true,
                 *       "isException": false,
                 *       "resultMessage": "CO校准参数设置成功"，
                 *       "errorMessage": "",
                 *       "result": 1835.8665f,
                 *       "status": ""
                 */
                log.info("Calibration result " + result.toString());

                // Is exception or not during calibration execution（编排器已在 result 上附带 phaseRecords，须入库，勿先 throw 否则 exceptionally 无法拿到 result）
                if (result.isException()) {
                    String resultContentJson = constructResult(core, parameters, result, envQualityControlRecords.getQualityControlType(), envQualityControlRecords.getId());
                    String eval = result.getErrorMessage() != null && !result.getErrorMessage().isEmpty()
                            ? result.getErrorMessage()
                            : result.getResultMessage();
                    envQualityControlRecords.setExecutionLog(resultContentJson);
                    envQualityControlRecords.setResultEvaluation(eval != null ? eval : "校准过程异常");
                    envQualityControlRecords.setEndTime(envQualityControlRecordsService.resolveTerminalEndTime(envQualityControlRecords.getId()));
                    envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
                    envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
                    executorMap.remove(envQualityControlRecords.getId());
                    return;
                }

                String resultContentJson = constructResult(core, parameters, result, envQualityControlRecords.getQualityControlType(), envQualityControlRecords.getId());

                // Is pass or not about the calibration
                if (result.isPass()) {
                    log.info("Calibration task completed successfully.");
                    final String message = "校准任务完成，且已通过 " + result.getResultMessage();
                    envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                    envQualityControlRecords.setExecutionLog(resultContentJson);
                    envQualityControlRecords.setResultEvaluation(message);
                    envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.SUCCESS.getCode());
                    envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
                } else {
                    log.error("Calibration task not pass: " + result.getResultMessage());
                    final String message = "校准任务完成，但未通过 " + result.getResultMessage();
                    envQualityControlRecords.setExecutionLog(resultContentJson);
                    envQualityControlRecords.setResultEvaluation(message);
                    envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                    envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.SUCCESS.getCode());
                    envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
                    // throw new RuntimeException(message);
                }
                executorMap.remove(envQualityControlRecords.getId());
            }).exceptionally(ex -> {
                Throwable cause = ex;
                if (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof ExecutorStoppedException) {
                    ExecutorResultBase stopResult = ((ExecutorStoppedException) cause).toResult();
                    AbstractCalibrationFlow flow = executorMap.remove(envQualityControlRecords.getId());
                    if (flow != null) {
                        List<PhaseExecutionRecord> recs = new ArrayList<>();
                        for (PhaseInfo pi : flow.getExecutorPhases()) {
                            if (pi == null) {
                                continue;
                            }
                            recs.add(new PhaseExecutionRecord(
                                    pi.getId(),
                                    pi.getDisplayName(),
                                    pi.getStartInstant(),
                                    pi.getEndInstant(),
                                    pi.getEstimatedSeconds()));
                        }
                        stopResult.setPhaseRecords(recs);
                    }
                    String resultContentJson = constructResult(core, parameters, stopResult, envQualityControlRecords.getQualityControlType(), envQualityControlRecords.getId());
                    envQualityControlRecords.setExecutionLog(resultContentJson);
                    envQualityControlRecords.setResultEvaluation(stopResult.getErrorMessage());
                    envQualityControlRecords.setEndTime(envQualityControlRecordsService.resolveTerminalEndTime(envQualityControlRecords.getId()));
                    envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
                    envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
                    return null;
                }
                log.error("Calibration task executed exception: " + ex.getMessage());
                String cleanMessage = ex.getMessage() != null ? ex.getMessage().replaceAll("^(java\\.lang\\.[A-Za-z]+: )", "") : "";
                final String message = "校准任务过程异常 " + cleanMessage;
                AbstractCalibrationFlow flow = executorMap.remove(envQualityControlRecords.getId());
                ExecutorResultBase stub = new ExecutorResultBase(false, true);
                stub.setErrorMessage(cleanMessage);
                if (flow != null) {
                    List<PhaseExecutionRecord> recs = new ArrayList<>();
                    for (PhaseInfo pi : flow.getExecutorPhases()) {
                        if (pi == null) {
                            continue;
                        }
                        recs.add(new PhaseExecutionRecord(
                                pi.getId(),
                                pi.getDisplayName(),
                                pi.getStartInstant(),
                                pi.getEndInstant(),
                                pi.getEstimatedSeconds()));
                    }
                    stub.setPhaseRecords(recs);
                }
                String resultContentJson = constructResult(core, parameters, stub, envQualityControlRecords.getQualityControlType(), envQualityControlRecords.getId());
                envQualityControlRecords.setExecutionLog(resultContentJson);
                envQualityControlRecords.setResultEvaluation(message);
                envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
                envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
                throw new RuntimeException(message);
            });
            log.info("task success.");
        } catch (Exception e) {
            log.error("task failed " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
