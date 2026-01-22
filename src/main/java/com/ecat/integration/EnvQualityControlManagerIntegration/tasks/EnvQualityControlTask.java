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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public class EnvQualityControlTask extends Task {
    private DeviceRegistry deviceRegistry;

    private IEnvQualityControlRecordsService envQualityControlRecordsService;
    private EcatCoreRuoyiIntegration mry;

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    public static final Map<String, String> parameterMap = new HashMap<>();

    private Map<Long, ExecutorBase> executorMap;

    public EnvQualityControlTask(Map<Long, ExecutorBase> executorMap){
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
     * @return 记录质控最终入库的参数+结果
     */
    protected String constructResult(Map<String, Object> params, ExecutorResultBase result, String envQualityControlTypeCode) {

        // deal result
        Map<String, Object> resultContent = new HashMap<>();
        resultContent.put("params", params);
        // TODO: 将结果添加到{"result": ...}中, 将执行状态等添加到{"statusMap": ...}中
        if (envQualityControlTypeCode.equals(QualityControlTypeEnum.ZERO_CHECK.getCode())) {
            CheckResult checkResult = (CheckResult) result;
            float resultValue = checkResult.getResult();  // 漂移量 nmol/mol
            float stdValue = checkResult.getStdValue();
            float deviceValue = checkResult.getDeviceValue();
            float checkPassLimit = checkResult.getCheckPassLimit();
            float checkCalibLimit = checkResult.getCheckCalibLimit();
            resultContent.put("resultValue", resultValue);
            resultContent.put("stdValue", stdValue);
            resultContent.put("deviceValue", deviceValue);
            resultContent.put("checkPassLimit", checkPassLimit);
            resultContent.put("checkCalibLimit", checkCalibLimit);
            resultContent.put("isPass", result.isPass());
            // 保留两位小数
            // double resultValue = ((CheckResult) result).getResult();
            // BigDecimal bdResultValue = new BigDecimal(resultValue);
            // resultValue = bdResultValue.setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.SPAN_CHECK.getCode())) {
            CheckResult checkResult = (CheckResult) result;
            float resultValue = checkResult.getResult();  // 漂移量 %
            float stdValue = checkResult.getStdValue();
            float deviceValue = checkResult.getDeviceValue();
            float checkPassLimit = checkResult.getCheckPassLimit();
            float checkCalibLimit = checkResult.getCheckCalibLimit();
            resultContent.put("resultValue", resultValue);
            resultContent.put("stdValue", stdValue);
            resultContent.put("deviceValue", deviceValue);
            resultContent.put("checkPassLimit", checkPassLimit);
            resultContent.put("checkCalibLimit", checkCalibLimit);
            resultContent.put("isPass", result.isPass());

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.MULTI_CHECK.getCode())) {
            MultiResult multiResult = (MultiResult) result;
            float slope = multiResult.getSlope(); // 斜率
            float intercept = multiResult.getIntercept(); // 截距
            float correlation = multiResult.getCorrelation(); // 相关系数
            List<Float> deviceValues = multiResult.getDeviceValues(); // 检查值列表
            List<Float> stdValues = multiResult.getStdValues(); // 标准值列表
            float check_r_min = multiResult.getCheck_r_min();
            float check_a_max = multiResult.getCheck_a_max();
            float check_a_min = multiResult.getCheck_a_min();
            float check_b_scope = multiResult.getCheck_b_scope();
            resultContent.put("slope", slope);
            resultContent.put("intercept", intercept);
            resultContent.put("correlation", correlation);
            resultContent.put("deviceValues", deviceValues);
            resultContent.put("stdValues", stdValues);
            resultContent.put("check_r_min", check_r_min);
            resultContent.put("check_a_max", check_a_max);
            resultContent.put("check_a_min", check_a_min);
            resultContent.put("check_b_scope", check_b_scope);
            resultContent.put("isPass", result.isPass());

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.PRECISION_CHECK.getCode())) {
            PrecisionResult precisionResult = (PrecisionResult) result;
            float precision = precisionResult.getPrecision(); // 精密度, 相对标准偏差
            float mean = precisionResult.getMean(); // 平均值
            float standardDeviation = precisionResult.getStandardDeviation(); // 标准偏差
            float checkRsd20Max = precisionResult.getCheckRsd20Max(); // 20%满量程的相对标准偏差最大值
            float deviceStdGas = precisionResult.getDeviceStdGas(); // 通入设备的标气浓度，nmol/mol or ppb
            List<Float> deviceValues = precisionResult.getDeviceValues(); // 响应值列表
            resultContent.put("precision", precision);
            resultContent.put("mean", mean);
            resultContent.put("standardDeviation", standardDeviation);
            resultContent.put("deviceStdGas", deviceStdGas);
            resultContent.put("checkRsd20Max", checkRsd20Max);
            resultContent.put("deviceValues", deviceValues);
            resultContent.put("isPass", result.isPass());

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.ACCURACY_CHECK.getCode())) {
            AccuracyResult accuracyResult = (AccuracyResult) result;
            float slope = accuracyResult.getSlope(); // 斜率
            float intercept = accuracyResult.getIntercept(); // 截距
            float correlation = accuracyResult.getCorrelation(); // 相关系数
            List<Float> deviceValues = accuracyResult.getDeviceValues(); // 检查值列表
            List<Float> stdValues = accuracyResult.getStdValues(); // 标准值列表
            float check_r_min = accuracyResult.getCheck_r_min();
            float check_a_max = accuracyResult.getCheck_a_max();
            float check_a_min = accuracyResult.getCheck_a_min();
            float check_b_scope = accuracyResult.getCheck_b_scope();
            resultContent.put("slope", slope);
            resultContent.put("intercept", intercept);
            resultContent.put("correlation", correlation);
            resultContent.put("deviceValues", deviceValues);
            resultContent.put("stdValues", stdValues);
            resultContent.put("check_r_min", check_r_min);
            resultContent.put("check_a_max", check_a_max);
            resultContent.put("check_a_min", check_a_min);
            resultContent.put("check_b_scope", check_b_scope);
            resultContent.put("isPass", result.isPass());

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.CONVERSION_CHECK.getCode())) {
            ConversionResult conversionResult = (ConversionResult) result;
            float efficiency = conversionResult.getEfficiency(); // 转换效率(%)
            List<Float> origNoDatas = conversionResult.getOrigNoDatas(); // 原始NO数据
            List<Float> origNoxDatas = conversionResult.getOrigNoxDatas(); // 原始NOx数据
            List<Float> remNoDatas = conversionResult.getRemNoDatas(); // 滴定NO数据
            List<Float> remNoxDatas = conversionResult.getRemNoxDatas(); // 滴定NOx数据
            Float origNoAvg = conversionResult.getOrigNoAvg(); // 原始NO平均值
            Float origNoxAvg = conversionResult.getOrigNoxAvg(); // 原始NOx平均值
            Float remNoAvg = conversionResult.getRemNoAvg(); // 滴定NO平均值
            Float remNoxAvg = conversionResult.getRemNoxAvg(); // 滴定NOx平均值
            resultContent.put("efficiency", efficiency);
            resultContent.put("origNoDatas", origNoDatas);
            resultContent.put("origNoxDatas", origNoxDatas);
            resultContent.put("origNoAvg", origNoAvg);
            resultContent.put("origNoxAvg", origNoxAvg);
            resultContent.put("remNoDatas", remNoDatas);
            resultContent.put("remNoAvg", remNoAvg);
            resultContent.put("remNoxDatas", remNoxDatas);
            resultContent.put("remNoxAvg", remNoxAvg);
            resultContent.put("result", result);
            resultContent.put("isPass", result.isPass());

        } else {
            throw new UnsupportedOperationException("暂不支持该类型质控");
        }
        // 将resultContent转为JSON字符串，以便于存储到数据库的text字段中
        String resultContentJson = JsonUtils.toJsonString(resultContent);
        return resultContentJson;
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

            // envDeviceCalibrationIntegration instantiation
            EnvDeviceCalibrationIntegration integration = (EnvDeviceCalibrationIntegration) core.getIntegrationRegistry().getIntegration("integration-env-device-calibration");

            // Check if any calibration task is running
            if (integration.isRunning()) {
                log.error("Calibration task exit: Other calibration task is currently running.");
                String message = "校准任务退出 已有执行中的校准任务";
                envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                envQualityControlRecords.setExecutionLog(message);  // 执行日志
                envQualityControlRecords.setResultEvaluation(message);  // 结果评价
                envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode());
                envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
                throw new RuntimeException(message);
            } else {
                log.info("Calibration task start, no other calibration task is running at the moment.");
                String message = "校准任务执行中...";
                envQualityControlRecords.setExecutionLog(message);
                envQualityControlRecords.setResultEvaluation(message);
                envQualityControlRecords.setEndTime(DateUtils.getNowDate());
                envQualityControlRecords.setExecutionStatus(ExecutionStatusEnum.RUNNING.getCode());
                envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords);
            }

            // Execute a specific calibration task
            String executerClass = QualityControlTypeEnum.valueOf(qualityControlType.toUpperCase()).getClassName();
            ExecutorBase executor = integration.getExecutor(ExecutorType.getEnum(executerClass));
            executorMap.put(envQualityControlRecords.getId(), executor);
            // Has available executor or not for this calibration task
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
            // Result of calibration task, support type:[ SO2, NOx, O3, CO ]
            parameter = parameter.equals("NO2")? "NOx": parameter;
            executor.execute(parameter).thenAccept(result -> {
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

                // Is exception or not during calibration execution
                if (result.isException()) {
                    throw new RuntimeException(result.getErrorMessage());
                }

                String resultContentJson = constructResult(parameters, result, envQualityControlRecords.getQualityControlType());

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
            }).exceptionally(ex -> {
                log.error("Calibration task executed exception: " + ex.getMessage());
                String cleanMessage = ex.getMessage().replaceAll("^(java\\.lang\\.[A-Za-z]+: )", "");
                final String message = "校准任务过程异常 " + cleanMessage;
                executorMap.remove(envQualityControlRecords.getId());
//                envQualityControlRecords.setExecutionLog(message);
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
