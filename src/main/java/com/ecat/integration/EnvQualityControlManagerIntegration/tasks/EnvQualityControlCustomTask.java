package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Task.Task;
import com.ecat.core.Utils.DynamicConfig.*;
import com.ecat.integration.EnvDeviceCalibrationIntegration.*;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;
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
 * @version 1.0
 * @description
 */

public class EnvQualityControlCustomTask extends Task {

    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DeviceRegistry deviceRegistry;

    private ExecutorService executor;

    private IEnvQualityControlRecordsService envQualityControlRecordsService;
    private EcatCoreRuoyiIntegration mry;

    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    private Map<Long, ExecutorBase> executorMap;

    public EnvQualityControlCustomTask(Map<Long, ExecutorBase> executorMap) {
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
     * 构造结果
     * @param params 入参
     * @param result 执行结果
     * @param envQualityControlTypeCode 质控类型code
     * @return 记录质控最终入库的参数+结果
     */
    protected String constructResult(Map<String, Object> params, ExecutorResultBase result, String envQualityControlTypeCode) {

        if (!envQualityControlTypeCode.equals(QualityControlTypeEnum.AUDIT_SPAN_CHECK.getCode())) {
            throw new UnsupportedOperationException("Only support audit check now.");
        }
        // deal result
        Map<String, Object> resultContent = new HashMap<>();
        List<Map<String, Object>> resultContentList = new ArrayList<>();
        if (result != null) {
            List<AuditCheckResultItem> deviceValues = ((AuditCheckResult) result).getDeviceValues();
            // 遍历deviceValues列表
            for (AuditCheckResultItem item : deviceValues) {
                Map<String, Object> resultContentItem = new HashMap<>();
                resultContentItem.put("checkData", item.getCheckData());
                resultContentItem.put("checkTime", item.getCheckTime().format(formatter));
                resultContentList.add(resultContentItem);
            }
            // TODO 将执行状态等信息一并存储，
            // result.isPass(), result.getResultMessage(), result.isException(), result.getErrorMessage()
            // Map<String, Object> statusMap = ...;
        } else {
            // statusMap = null;
        }
        resultContent.put("result", resultContentList);
        resultContent.put("params", params);
        // resultContent.put("statusMap", statusMap);

        return JsonUtils.toJsonString(resultContent);
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

        // envDeviceCalibrationIntegration instantiation
        EnvDeviceCalibrationIntegration integration = (EnvDeviceCalibrationIntegration) core.getIntegrationRegistry().getIntegration("integration-env-device-calibration");

        // Check if any calibration task is running
        if (integration.isRunning()) {
            log.error("Calibration task exit: Other calibration task is currently running.");
            String message = "校准任务退出 已有执行中的校准任务";
            String resultContentJson = constructResult(parameters, null, envQualityControlRecords.getQualityControlType());
            envQualityControlRecords.setExecutionLog(resultContentJson);  // 执行日志
            envQualityControlRecords.setResultEvaluation(message);  // 结果评价
            envQualityControlRecords.setEndTime(DateUtils.getNowDate());
            envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
            envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
            throw new RuntimeException(message);
        } else {
            log.info("Calibration task start, no other calibration task is running at the moment.");
            String message = "校准任务执行中...";
            String resultContentJson = constructResult(parameters, null, envQualityControlRecords.getQualityControlType());
            envQualityControlRecords.setExecutionLog(resultContentJson);
            envQualityControlRecords.setResultEvaluation(message);
            envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.RUNNING.getCode());
            envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
        }

        // Has available executor or not for this calibration task
        // Execute a specific calibration task
        String executerClass = QualityControlTypeEnum.valueOf(qualityControlType.toUpperCase()).getClassName();
        ExecutorBase executor = integration.getExecutor(ExecutorType.getEnum(executerClass));
        executorMap.put(envQualityControlRecords.getId(), executor);
        if (executor == null) {
            log.error("Calibration task failed: No calibration task executor found.");
            String message = "校准任务未执行 " + "没有可用的执行器";
            String resultContentJson = constructResult(parameters, null, envQualityControlRecords.getQualityControlType());
            envQualityControlRecords.setExecutionLog(resultContentJson);
            envQualityControlRecords.setResultEvaluation(message);
            envQualityControlRecords.setEndTime(DateUtils.getNowDate());
            envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
            envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
            throw new RuntimeException(message);
        }
        // Result of calibration task, support type:[ SO2, NOx, O3, CO ]
        gas = gas.equals("NO2")? "NOx": gas;
        AuditCheckExecuteParam params = new AuditCheckExecuteParam(gas);
        params.setGenGasTime(genGasTime);
        params.setReadDataCount(readDataCount);
        params.setReadDataSpan(readDataSpan);
        params.setGenGasConc(genGasConc);
        params.setStdGasInPortName(stdGasInPortName);

        // 调用base类的execute方法
        // 同步获取结果.get();
        // future.get();
        // 异步获取结果.thenAccept();
        executor.execute(params).thenAccept(result -> {
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

            String resultContentJson = constructResult(parameters, result, envQualityControlRecords.getQualityControlType());

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
            String resultContentJson = constructResult(parameters, null, envQualityControlRecords.getQualityControlType());
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
