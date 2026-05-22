package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Task.Task;
import com.ecat.core.Utils.DynamicConfig.*;
import com.ecat.integration.EnvCalibrationComposerIntegration.*;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.TaskTypeEnum;
import com.ruoyi.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * EnvQualityControlCustomTask
 *
 * @author caohongbo
 * @version 2.0
 * @description
 */

public class EnvQualityControlCustomTask extends Task {

    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DeviceRegistry deviceRegistry;

    private ExecutorService executor;

    private IEnvQualityControlRecordsService envQualityControlRecordsService;
    private EcatCoreRuoyiIntegration mry;

    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    private Map<Long, AbstractCalibrationFlow> executorMap;

    public EnvQualityControlCustomTask(Map<Long, AbstractCalibrationFlow> executorMap) {
        this.executorMap = executorMap;
    }

    @Override
    public String getTaskName() {
        return "EnvQualityControlCustomTask";
    }

    @Override
    public String getDescription() {
        return "人工核查任务";
    }

    @Override
    protected ConfigDefinition getConfigDefinition() {
        // 定义配置项

        Set<String> validTriggerTypekValues = new HashSet<>(Arrays.asList("0", "1"));
        Set<String> validTaskTypeValues = TaskTypeEnum.getAllTaskTypeCodes();
        Set<String> validParameterValues = ParameterEnum.getAllParameterNameSet();
        Set<String> validQualityControlTypeValues = QualityControlTypeEnum.getAllQualityControlTypeNameSet();
        Set<String> validStdGasInPortNameValues = new HashSet<>(Arrays.asList("跨度口", "采样口", "跨度检查", "测量"));

        ConfigDefinition configDefinition = new ConfigDefinition();
        ConfigItemBuilder builder = new ConfigItemBuilder()
                .add(new ConfigItem<>("triggerType", String.class, false, null, new StringEnumValidator(validTriggerTypekValues)))
                .add(new ConfigItem<>("taskType", String.class, false, null, new StringEnumValidator(validTaskTypeValues)))
                .add(new ConfigItem<>("qualityControlType", String.class, false, null, new StringEnumValidator(validQualityControlTypeValues)))
                .add(new ConfigItem<>("gas", String.class, true, null, new StringEnumValidator(validParameterValues)))
                .add(new ConfigItem<>("genGasTime", Integer.class, true, null ))
                .add(new ConfigItem<>("readDataCount", Integer.class, true, null ))
                .add(new ConfigItem<>("readDataSpan", Integer.class, true, null ))
                .add(new ConfigItem<>("genGasConc", Float.class, true, null ))
                .add(new ConfigItem<>("stdGasInPortName", String.class, false, null, new StringEnumValidator(validStdGasInPortNameValues)))
                .add(new ConfigItem<>("targetFlowLpm", Double.class, false, 4.0d));

        configDefinition.define(builder);
        return configDefinition;
    }

    /**
     * @param core 用于生成关键参数快照，可为 null
     */
    protected String constructResult(EcatCore core, Map<String, Object> params, ExecutorResultBase result,
                                       String envQualityControlTypeCode, long qcRecordIdForSnapshot) {

        if (!envQualityControlTypeCode.equals(QualityControlTypeEnum.AUDIT_SPAN_CHECK.getCode())) {
            throw new UnsupportedOperationException("Only support audit check now.");
        }
        List<Map<String, Object>> resultContentList = new ArrayList<>();
        if (result != null && !result.isException()) {
            List<AuditCheckResultItem> deviceValues = ((AuditCheckResult) result).getDeviceValues();
            for (AuditCheckResultItem item : deviceValues) {
                Map<String, Object> resultContentItem = new LinkedHashMap<>();
                resultContentItem.put("checkData", item.getCheckData());
                resultContentItem.put("checkTime", item.getCheckTime().format(formatter));
                resultContentList.add(resultContentItem);
            }
        }

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

        List<Map<String, Object>> keySnap = Collections.emptyList();
        if (qcRecordIdForSnapshot >= 0) {
            String gasName = params != null ? (String) params.get("gas") : null;
            keySnap = buildKeyParametersSnapshotAtComplete(core, gasName);
        }

        return QualityControlExecutionLogHelper.toExecutionLogJson(serializableParams, resultContentList, result, keySnap);
    }

    /**
     * 编排在校准 flow 异步启动之前即失败（例如 ComposerContext 中设备未配置）
     */
    private RuntimeException persistAndWrapCalibrationLaunchFailure(
            EcatCore core,
            Map<String, Object> parameters,
            EnvQualityControlRecords envQualityControlRecords,
            Throwable ex) {
        log.error("Calibration task failed before async execution: {}", ex.getMessage(), ex);
        String cleanMessage = ex.getMessage() != null ? ex.getMessage().replaceAll("^(java\\.lang\\.[A-Za-z]+: )", "") : "";
        final String message = "校准任务过程异常 " + cleanMessage;
        ExecutorResultBase stub = new ExecutorResultBase(false, true);
        stub.setErrorMessage(cleanMessage);
        String resultContentJson = constructResult(core, parameters, stub, envQualityControlRecords.getQualityControlType(), envQualityControlRecords.getId());
        envQualityControlRecords.setExecutionLog(resultContentJson);
        envQualityControlRecords.setResultEvaluation(message);
        envQualityControlRecords.setEndTime(DateUtils.getNowDate());
        envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
        envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
        return new RuntimeException(message, ex);
    }

    private List<Map<String, Object>> buildKeyParametersSnapshotAtComplete(EcatCore core, String gasParameterName) {
        if (core == null || gasParameterName == null || gasParameterName.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> rows = LogicDeviceReportSupport.buildAnalyzerKeyParameters(core, gasParameterName);
            return rows != null ? rows : Collections.emptyList();
        } catch (Exception e) {
            log.debug("audit keyParametersSnapshot skipped: {}", e.getMessage());
            return Collections.emptyList();
        }
    }


    // 自定义质控页面调用
    public void callExecuteImpl(Map<String, Object> parameters) {
        executeImpl(parameters);
    }

    // 自定义任务逻辑
    @Override
    protected void executeImpl(Map<String, Object> parameters) {
    try {
        // 设置属性
        String triggerType = (String) parameters.get("triggerType");
        String taskType = triggerType;
        String qualityControlType = (String) parameters.get("qualityControlType");
        EcatCore core = (EcatCore) parameters.remove("core");  // 弹出core以便入库存储

        String gas = (String) parameters.get("gas");
        int genGasTime = (int) parameters.get("genGasTime");
        int readDataCount = (int) parameters.get("readDataCount");
        int readDataSpan = (int) parameters.get("readDataSpan");
        float genGasConc = (float) parameters.get("genGasConc");
        String stdGasInPortName = normalizeStdGasInPort((String) parameters.get("stdGasInPortName"));
        parameters.put("stdGasInPortName", stdGasInPortName);
        Number flowRateNum = (Number) parameters.get("flowRateLpm");
        if (flowRateNum == null) {
            flowRateNum = (Number) parameters.get("targetFlowLpm");
        }
        if (flowRateNum == null) {
            flowRateNum = 4.0d;
        }

        // （提前）组装质控记录数据
        // 增加记录
        EnvQualityControlRecords envQualityControlRecords = new EnvQualityControlRecords();
        envQualityControlRecords.setTaskType(taskType);
        envQualityControlRecords.setQualityControlType(QualityControlTypeEnum.valueOf(qualityControlType.toUpperCase()).getCode());
        envQualityControlRecords.setParameter(ParameterEnum.valueOf(gas).getCode());
        envQualityControlRecords.setStartTime(DateUtils.getNowDate());
        envQualityControlRecords.setCreatedBy("admin"); // 临时
        envQualityControlRecords.setUpdatedBy("admin");
        envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.WAITING.getCode());  // 执行状态：0=等待中，1=运行中，2=成功结束，3=异常结束
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

        // env-calibration-composer
        EnvCalibrationComposerIntegration integration = (EnvCalibrationComposerIntegration) core.getIntegrationRegistry().getIntegration("integration-env-calibration-composer");

        // 占用判断（与 EnvCalibrationComposerIntegration#isRunning() 一致）
        if (Boolean.TRUE.equals(integration.isRunning())) {
            log.error("Calibration task exit: Other calibration task is currently running.");
            String message = "校准任务退出 已有执行中的校准任务";
            String resultContentJson = constructResult(core, parameters, null, envQualityControlRecords.getQualityControlType(), -1L);
            envQualityControlRecords.setExecutionLog(resultContentJson);  // 执行日志
            envQualityControlRecords.setResultEvaluation(message);  // 结果评价
            envQualityControlRecords.setEndTime(DateUtils.getNowDate());
            envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
            envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
            throw new RuntimeException(message);
        } else {
            log.info("Calibration task start, no other calibration task is running at the moment.");
            String message = "校准任务执行中...";
            String resultContentJson = constructResult(core, parameters, null, envQualityControlRecords.getQualityControlType(), -1L);
            envQualityControlRecords.setExecutionLog(resultContentJson);
            envQualityControlRecords.setResultEvaluation(message);
            envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.RUNNING.getCode());
            envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
        }

        // Has available executor or not for this calibration task
        // Execute a specific calibration task
        String executerClass = QualityControlTypeEnum.valueOf(qualityControlType.toUpperCase()).getClassName();
        ExecutorType execType = ExecutorType.getEnum(executerClass);
        String gasForComposer = LogicDeviceBindingIds.composerGasKeyFromParameterName(gas);

        Map<String, Object> flowParams = new HashMap<>();
        flowParams.put("stableTimeSeconds", genGasTime);
        flowParams.put("sampleCount", readDataCount);
        flowParams.put("sampleIntervalSeconds", readDataSpan);
        flowParams.put("spanConcentrationPpb", genGasConc * 1000.0f);
        if (flowRateNum != null) {
            flowParams.put("flowRateLpm", flowRateNum.floatValue());
        }

        CompletableFuture<ExecutorResultBase> calibrationFuture;
        try {
            calibrationFuture = integration.execute(execType, gasForComposer, flowParams);
        } catch (Exception launchEx) {
            throw persistAndWrapCalibrationLaunchFailure(core, parameters, envQualityControlRecords, launchEx);
        }

        AbstractCalibrationFlow executor = integration.getRunningExecutor();
        executorMap.put(envQualityControlRecords.getId(), executor);
        if (executor == null) {
            log.error("Calibration task failed: No calibration task executor found.");
            String message = "校准任务未执行 " + "没有可用的执行器";
            String resultContentJson = constructResult(core, parameters, null, envQualityControlRecords.getQualityControlType(), -1L);
            envQualityControlRecords.setExecutionLog(resultContentJson);
            envQualityControlRecords.setResultEvaluation(message);
            envQualityControlRecords.setEndTime(DateUtils.getNowDate());
            envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
            envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
            throw new RuntimeException(message);
        }
        if (stdGasInPortName != null) {
            log.debug("人工核查 stdGasInPortName={} — 编排器使用固定气路逻辑，此字段已忽略", stdGasInPortName);
        }

        calibrationFuture.thenAccept(result -> {
            /**
             * 获取执行结果
             * 以单点为例：
             * AuditResult result
             *       "isPass": true,
             *       "isException": false,
             *       "resultMessage": "CO校准参数设置成功"，
             *       "errorMessage": "",
             *       "result": ,
             *       "status": ""
             */
            log.info("Calibration result " + result.toString());

            // Is exception or not during calibration execution（须先入库 phaseRecords，勿先 throw）
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
                final String message = "校准任务成功完成 " + result.getResultMessage();
                envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                envQualityControlRecords.setExecutionLog(resultContentJson);
                envQualityControlRecords.setResultEvaluation(message);
                envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.SUCCESS.getCode());
                envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
            } else {
                log.error("Calibration task not pass: " + result.getResultMessage());
                final String message = "校准任务未通过 " + result.getResultMessage();
                envQualityControlRecords.setExecutionLog(resultContentJson);
                envQualityControlRecords.setResultEvaluation(message);
                envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
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

    /** 统一标气入口展示与入库：默认跨度口；兼容历史「跨度检查/测量」调度入参。 */
    private static String normalizeStdGasInPort(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "跨度口";
        }
        String s = raw.trim();
        if ("跨度检查".equals(s)) {
            return "跨度口";
        }
        if ("测量".equals(s)) {
            return "采样口";
        }
        return s;
    }
}
