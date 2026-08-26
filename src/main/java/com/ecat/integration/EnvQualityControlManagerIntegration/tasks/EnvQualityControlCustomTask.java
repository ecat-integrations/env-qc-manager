package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Task.Task;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.DynamicConfig.ConfigItem;
import com.ecat.core.Utils.DynamicConfig.ConfigItemBuilder;
import com.ecat.core.Utils.DynamicConfig.StringEnumValidator;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase;
import com.ecat.integration.EnvCalibrationComposerIntegration.AuditCheckResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.AuditCheckResultItem;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcResultFormatter;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.QcExecutionRequest;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.TriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.FlowDefaults;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.TaskTypeEnum;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Arrays;

/**
 * EnvQualityControlCustomTask
 * <p>人工核查任务（audit_span_check）：适配 quartz/hj212 入参，组统一请求交编排器执行。</p>
 *
 * @author caohongbo
 * @version 2.0
 */

public class EnvQualityControlCustomTask extends Task implements QcResultFormatter {

    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    public EnvQualityControlCustomTask() {
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
                .add(new ConfigItem<>("targetFlowLpm", Double.class, false, FlowDefaults.DEFAULT_TARGET_FLOW_LPM));

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
        String stdGasSnap = "";
        if (qcRecordIdForSnapshot >= 0) {
            String gasName = params != null ? (String) params.get("gas") : null;
            keySnap = buildKeyParametersSnapshotAtComplete(core, gasName);
            if (core != null && gasName != null && !gasName.isEmpty()) {
                try {
                    stdGasSnap = LogicDeviceReportSupport.readStandardGasCylinderConcentration(core, gasName);
                } catch (Exception e) {
                    log.debug("stdGasConcentration snapshot skipped: {}", e.getMessage());
                }
            }
        }

        return QualityControlExecutionLogHelper.toExecutionLogJson(serializableParams, resultContentList, result, keySnap, stdGasSnap);
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

    /** QcResultFormatter：人工核查任务只负责 audit_span_check。 */
    @Override
    public boolean supports(String qualityControlTypeCode) {
        return QualityControlTypeEnum.AUDIT_SPAN_CHECK.getCode().equals(qualityControlTypeCode);
    }

    @Override
    public String format(EcatCore core, Map<String, Object> params, ExecutorResultBase result,
                         String qualityControlTypeCode, long qcRecordId) {
        return constructResult(core, params, result, qualityControlTypeCode, qcRecordId);
    }

    @Override
    public String formatStub(EcatCore core, Map<String, Object> params, ExecutorResultBase stub,
                             String qualityControlTypeCode, long qcRecordId) {
        // 人工核查的 constructResult 对桩结果本身安全（isException 走空结果列表分支），无独立 stub 通道
        return constructResult(core, params, stub, qualityControlTypeCode, qcRecordId);
    }

    /**
     * 薄适配层：人工核查页面/调度参数 → 统一触发编排器（G-STRUCT-1）。
     * 参数解析沿用旧契约（gas/genGasTime/readDataCount/readDataSpan/genGasConc/stdGasInPortName/targetFlowLpm）；
     * 时长参数映射进 durationOverrides（key 沿用 composer flowParams 契约），浓度换算 ppb=ppm×1000。
     */
    @Override
    protected void executeImpl(Map<String, Object> parameters) {
        try {
            String triggerType = (String) parameters.get("triggerType");
            String qualityControlType = (String) parameters.get("qualityControlType");
            EcatCore core = (EcatCore) parameters.remove("core");  // 弹出core以便编排器使用

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
                flowRateNum = FlowDefaults.DEFAULT_TARGET_FLOW_LPM;
            }

            // G-BUG-8：不再持有惰性 mry 字段（任务实例可被并发调度，字段竞态）；每次执行按 core 现取编排器 bean
            EcatCoreRuoyiIntegration mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry()
                    .getIntegration("integration-ecat-core-ruoyi");
            QcmExecutionOrchestrator orchestrator = mry.getSpringBean(QcmExecutionOrchestrator.class);
            if (orchestrator == null) {
                throw new IllegalStateException("QcmExecutionOrchestrator bean 不存在，人工核查任务无法触发");
            }

            Object user = parameters.get("user");
            String triggerUser = user instanceof String && !((String) user).trim().isEmpty()
                    ? (String) user : "system";

            Map<String, Object> durationOverrides = new LinkedHashMap<>();
            durationOverrides.put("stableTimeSeconds", genGasTime);
            durationOverrides.put("sampleCount", readDataCount);
            durationOverrides.put("sampleIntervalSeconds", readDataSpan);

            QcExecutionRequest request = QcExecutionRequest.builder()
                    .qcType(qualityControlType)
                    .instruments(Collections.singletonList(gas))
                    .concentrationPpb(BigDecimal.valueOf(genGasConc * 1000.0f))
                    .flowRateLpm(BigDecimal.valueOf(flowRateNum.doubleValue()))
                    .durationOverrides(durationOverrides)
                    .build();

            BatchResult result = orchestrator.triggerExecution(request,
                    TriggerSource.fromTaskTypeCode(triggerType), triggerUser);
            if (result.getStatus() != BatchResult.Status.ACCEPTED) {
                // 互斥闸拒绝已落库留痕；向任务框架重抛保持旧可观测契约（QcmCustomServiceImpl 按失败返回 false）
                log.error("task rejected: {} batch={}", result.getFailureReason(), result.getBatchId());
                throw new RuntimeException("校准任务退出 已有执行中的校准任务");
            }
            log.info("task success. batch={}", result.getBatchId());
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
