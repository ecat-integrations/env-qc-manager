package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Task.Task;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.DynamicConfig.ConfigItem;
import com.ecat.core.Utils.DynamicConfig.ConfigItemBuilder;
import com.ecat.core.Utils.DynamicConfig.StringEnumValidator;
import com.ecat.core.Utils.DynamicConfig.StringLengthValidator;
import com.ecat.integration.EnvCalibrationComposerIntegration.AccuracyResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.CheckResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.ConversionResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase;
import com.ecat.integration.EnvCalibrationComposerIntegration.MultiResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.PrecisionResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcResultFormatter;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.CylinderArchiveSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.TaskTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标准质控任务（薄适配层，G-STRUCT-1）：quartz/hj212 参数签名与语义不变（triggerType/
 * qualityControlType/parameter/targetFlowLpm），组 {@link QcExecutionRequest} 交
 * {@link QcmExecutionOrchestrator} 统一编排；同时以 {@link QcResultFormatter} 身份向编排器
 * 提供本类 constructResult 的结果格式化能力（5.2 再合并人工核查版）。
 */
public class EnvQualityControlTask extends Task implements QcResultFormatter {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    public EnvQualityControlTask(){
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

        Map<String, Object> serializableParams = serializableTaskParamsForLog(params);

        Map<String, Object> metrics = new LinkedHashMap<>();

        // 零点/跨度共用同一 CheckResult 载荷结构（G-STRUCT-2：合并原 6 行重复分支，行为由
        // EnvQualityControlTaskConstructResultTest 锁死）
        if (envQualityControlTypeCode.equals(QualityControlTypeEnum.ZERO_CHECK.getCode())
                || envQualityControlTypeCode.equals(QualityControlTypeEnum.SPAN_CHECK.getCode())) {
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
            if (checkResult.getVerificationValue() != null) {
                metrics.put("verificationValue", checkResult.getVerificationValue());
            }

        } else if (envQualityControlTypeCode.equals(QualityControlTypeEnum.MULTI_CHECK.getCode())) {
            // composer MultiPointCheckFlow 实际返回 MultiResult（斜率/截距/相关系数/点位序列）；
            // 历史上此处曾有一段条件完全重复的 CheckResult 分支抢先命中，MultiResult 进来即
            // ClassCastException（bugs/bug-record-20260902-161500，TDD 修复：multiCheckBranch_acceptsMultiResult）
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
        String stdGasSnap = "";
        String stdGasUnitSnap = "";
        if (qcRecordId < 0) {
            keySnapshot = Collections.emptyList();
        } else {
            String paramStr = params != null ? (String) params.get("parameter") : null;
            String gasName = paramStr != null ? ParameterEnum.valueOf(paramStr).name() : null;
            keySnapshot = buildKeyParametersSnapshotAtComplete(core, gasName);
            String[] stdGasPair = stdGasSnapshotPair(core, paramStr);
            stdGasSnap = stdGasPair[0];
            stdGasUnitSnap = stdGasPair[1];
        }
        return QualityControlExecutionLogHelper.toExecutionLogJson(serializableParams, metrics, result, keySnapshot,
                stdGasSnap, stdGasUnitSnap);
    }

    private static Map<String, Object> serializableTaskParamsForLog(Map<String, Object> params) {
        Map<String, Object> serializableParams = new LinkedHashMap<>();
        if (params == null) {
            return serializableParams;
        }
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
        return serializableParams;
    }


    private String executionLogJsonForStubResult(EcatCore core, Map<String, Object> params, ExecutorResultBase stub, long qcRecordId) {
        Map<String, Object> serializableParams = serializableTaskParamsForLog(params);
        List<Map<String, Object>> keySnapshot;
        String stdGasSnap = "";
        String stdGasUnitSnap = "";
        if (qcRecordId < 0) {
            keySnapshot = Collections.emptyList();
        } else {
            String paramStr = params != null ? (String) params.get("parameter") : null;
            String gasName = paramStr != null ? ParameterEnum.valueOf(paramStr).name() : null;
            keySnapshot = buildKeyParametersSnapshotAtComplete(core, gasName);
            String[] stdGasPair = stdGasSnapshotPair(core, paramStr);
            stdGasSnap = stdGasPair[0];
            stdGasUnitSnap = stdGasPair[1];
        }
        return QualityControlExecutionLogHelper.toExecutionLogJson(serializableParams, Collections.emptyMap(), stub,
                keySnapshot, stdGasSnap, stdGasUnitSnap);
    }

    /**
     * 标气快照对（[0]=浓度裸数串、[1]=单位短名；无档案/未登记时对应元素空串）。
     *
     * <p>走 {@link CylinderArchiveSupport#readArchive}（airstation 钢瓶逻辑设备 AttrState 一次读）
     * 成对取浓度+单位——与 {@code ResultSnapshotWriter} 冻结 {@code gas_concentration(_unit)} 列同一条好链。
     * 旧链 {@code LogicDeviceReportSupport.readStandardGasCylinderConcentration} 返回裸串无单位，
     * execution_log 消费方无法判定浓度口径（ppm/ppb），是用户发现的上报缺陷。读取失败只降级为空快照
     * （完成路径不能因档案缺失而失败，与旧链容错一致）。</p>
     */
    private String[] stdGasSnapshotPair(EcatCore core, String paramStr) {
        if (core == null || paramStr == null) {
            return new String[] {"", ""};
        }
        try {
            CylinderArchiveSupport.GasTrace trace =
                    CylinderArchiveSupport.readArchive(core, ParameterEnum.valueOf(paramStr).getCode());
            if (trace != null && trace.concentration != null) {
                return new String[] {
                        trace.concentration.toPlainString(),
                        trace.concentrationUnit != null ? trace.concentrationUnit.trim() : ""};
            }
        } catch (Exception e) {
            log.debug("stdGasConcentration snapshot skipped: {}", e.getMessage());
        }
        return new String[] {"", ""};
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

    /** QcResultFormatter：标准质控负责除人工核查外的全部质控类型。 */
    @Override
    public boolean supports(String qualityControlTypeCode) {
        return !QualityControlTypeEnum.AUDIT_SPAN_CHECK.getCode().equals(qualityControlTypeCode);
    }

    @Override
    public String format(EcatCore core, Map<String, Object> params, ExecutorResultBase result,
                         String qualityControlTypeCode, long qcRecordId) {
        return constructResult(core, params, result, qualityControlTypeCode, qcRecordId);
    }

    @Override
    public String formatStub(EcatCore core, Map<String, Object> params, ExecutorResultBase stub,
                             String qualityControlTypeCode, long qcRecordId) {
        return executionLogJsonForStubResult(core, params, stub, qcRecordId);
    }

    /**
     * 薄适配层：解析 quartz/hj212 契约参数 → 统一触发编排器（G-STRUCT-1）。
     * 签名与参数语义不变（triggerType/qualityControlType/parameter/targetFlowLpm）。
     */
    @Override
    protected void executeImpl(Map<String, Object> parameters) {
        try {
            String triggerType = (String) parameters.get("triggerType");
            String qualityControlType = (String) parameters.get("qualityControlType");
            String parameter = (String) parameters.get("parameter");
            EcatCore core = (EcatCore) parameters.remove("core");  // 弹出core以便编排器使用
            Number flowRateNum = (Number) parameters.get("targetFlowLpm");

            // G-BUG-8：不再持有惰性 mry 字段（任务实例可被并发调度，字段竞态）；每次执行按 core 现取编排器 bean
            EcatCoreRuoyiIntegration mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry()
                    .getIntegration("integration-ecat-core-ruoyi");
            QcmExecutionOrchestrator orchestrator = mry.getSpringBean(QcmExecutionOrchestrator.class);
            if (orchestrator == null) {
                throw new IllegalStateException("QcmExecutionOrchestrator bean 不存在，质控任务无法触发");
            }

            // quartz 参数无用户上下文：parameters 带 user key 则用之，否则 system（G-STD-4：废弃 "admin" 硬编码；
            // 页面手动执行的真实用户由 2.4 controller 直调编排器传入）
            Object user = parameters.get("user");
            String triggerUser = user instanceof String && !((String) user).trim().isEmpty()
                    ? (String) user : "system";

            QcExecutionRequest request = QcExecutionRequest.builder()
                    .qcType(qualityControlType)
                    .instruments(Collections.singletonList(parameter))
                    .flowRateLpm(flowRateNum != null ? BigDecimal.valueOf(flowRateNum.doubleValue()) : null)
                    .build();

            BatchResult result = orchestrator.triggerExecution(request,
                    TriggerSource.fromTaskTypeCode(triggerType), triggerUser);
            if (result.getStatus() != BatchResult.Status.ACCEPTED) {
                // 互斥闸拒绝已落库留痕；向任务框架重抛保持旧可观测契约（调用方按失败处理）
                log.error("task rejected: {} batch={}", result.getFailureReason(), result.getBatchId());
                throw new RuntimeException("校准任务退出 已有执行中的校准任务");
            }
            log.info("task success. batch={}", result.getBatchId());
        } catch (Exception e) {
            log.error("task failed " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
