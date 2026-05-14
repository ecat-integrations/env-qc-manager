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
        Set<String> validStdGasInPortNameValues = new HashSet<>(Arrays.asList("跨度检查", "测量"));

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
                .add(new ConfigItem<>("stdGasInPortName", String.class, false, null, new StringEnumValidator(validStdGasInPortNameValues)));

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
        String stdGasInPortName = (String) parameters.get("stdGasInPortName");
        Number flowRateNum = (Number) parameters.get("flowRateLpm");
        if (flowRateNum == null) {
            flowRateNum = (Number) parameters.get("targetFlowLpm");
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
        // 前端与旧版约定浓度单位为 ppm，ComposerContext 使用 ppb
        flowParams.put("spanConcentrationPpb", genGasConc * 1000.0f);
        if (flowRateNum != null) {
            flowParams.put("flowRateLpm", flowRateNum.floatValue());
        }

        // 通过编排器 execute(type, gas, params) 启动，登记 runningFlow；自定义通气/采样/浓度覆盖参数
        CompletableFuture<ExecutorResultBase> calibrationFuture =
            integration.execute(execType, gasForComposer, flowParams);

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

            // Is exception or not during calibration execution
            if (result.isException()) {
                throw new RuntimeException(result.getErrorMessage());
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
        }).exceptionally(ex -> {
            log.error("Calibration task executed exception: " + ex.getMessage());
            String cleanMessage = ex.getMessage().replaceAll("^(java\\.lang\\.[A-Za-z]+: )", "");
            final String message = "校准任务过程异常 " + cleanMessage;
            String resultContentJson = constructResult(core, parameters, null, envQualityControlRecords.getQualityControlType(), envQualityControlRecords.getId());
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
