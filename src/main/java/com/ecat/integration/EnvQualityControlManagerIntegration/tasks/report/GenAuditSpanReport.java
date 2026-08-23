package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.appendConcUnit;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.appendKeySnapshotRows;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.enrichKeyParameterRowsForReport;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.firstNonBlank;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.fmtReportTime;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.plainEvaluationIfNotJson;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.recordCreatorRef;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.recordUpdaterRef;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum.CO;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.AUDIT_SPAN_CHECK;

/**
 * GenAuditSpanReport
 * <p>生成人工零跨核查记录表：同一气体同批的全部人工核查记录聚合到一张报告。</p>
 * @version 1.0
 */
public class GenAuditSpanReport extends ReportGenerator {

    private final EcatCore core;

    @Getter
    private AuditSpanReport report;

    public static final String FULL_SPAN = "500ppb";
    public static final String FULL_SPAN_CO = "50ppm";

    /**
     * 实际执行此方法 生成报告
     * @param records 同参数的人工核查记录列表
     */
    public GenAuditSpanReport(EcatCore core, List<QcmRecord> records) {
        this(core, records, null);
    }

    /** AC-C8：传入 generate() 预取的参数级设备缓存（可 null=逐条查询）。 */
    public GenAuditSpanReport(EcatCore core, List<QcmRecord> records, Map<String, DeviceBase> devicePrefetch) {
        super(core, devicePrefetch);
        this.core = core;
        report = new AuditSpanReport();
        report = parseRecordToReport(records);
        report.setComponent(ReportTypeEnum.AUDIT_SPAN.getComponent());
        attachReportData(report);
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param records 质控记录
     * @return report 质控报告
     */
    private AuditSpanReport parseRecordToReport(List<QcmRecord> records) {
        QcmRecord records_0 = records.get(0);

        report.setReportDate(ReportFormatSupport.reportDateOf(records_0.getCreateTime()));
        String filerRef = recordCreatorRef(records_0);
        String reviewerRef = firstNonBlank(recordUpdaterRef(records_0), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? records_0.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(records_0.getUpdatedBy()) : reviewerRef);
        report.setGasType(records_0.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);
        applyStandardGasSourceAndNo(report, param, records_0);
        refreshReportGasSourceAndNoCombined(report);
        DeviceBase device = param != null ? getDeviceInfo(param) : null;
        if (device != null) {
            report.setInstrumentName(device.getName() != null ? device.getName() : "");
            report.setInstrumentNo(device.getSn() != null ? device.getSn() : "");
            report.setInstrumentNameAndNo(
                    (device.getName() != null ? device.getName() : "") + (device.getSn() != null ? device.getSn() : ""));
            report.setReportName(device.getName() + report.getReportName());
        } else {
            report.setInstrumentName("");
            report.setInstrumentNo("");
            report.setInstrumentNameAndNo("");
            report.setReportName(report.getReportName());
        }
        String gasConcentration = param != null ? getStdGasConcentration(param) : "";
        report.setGasConcentration(gasConcentration == null ? "" : gasConcentration);

        List<String> auditRemarkLines = new ArrayList<>();
        boolean coByParameter = CO.getCode().equals(report.getGasType());
        for (QcmRecord record : records) {
            if (record.getExecutionLog() != null) {
                Map<String, Object> rootQuick = QualityControlExecutionLogHelper.parseRootMap(record.getExecutionLog());
                String ar = QualityControlExecutionLogHelper.remarkSummaryForFailedQC(record.getExecutionLog(), "人工核查");
                if (ar.isEmpty() && !rootQuick.isEmpty() && !QualityControlExecutionLogHelper.readIsPass(record.getExecutionLog())) {
                    ar = plainEvaluationIfNotJson(record.getResultEvaluation());
                }
                if (!ar.isEmpty()) {
                    auditRemarkLines.add(ar);
                }
            }
            if (!AUDIT_SPAN_CHECK.getCode().equals(record.getQualityControlType())) {
                throw new RuntimeException("Invalid report type for AuditSpanReport: " + record.getQualityControlType());
            }
            report.setSpanStartTime(fmtReportTime(record.getStartTime()));
            report.setSpanEndTime(fmtReportTime(record.getEndTime()));

            Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(record.getExecutionLog());
            if (root.isEmpty()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> params = root.get("params") instanceof Map
                    ? (Map<String, Object>) root.get("params")
                    : Collections.emptyMap();
            Number genGasConc = toNumber(params.get("genGasConc"));
            String checkDisplay = formatAuditSetpointDisplay(genGasConc, coByParameter);
            report.setCheckConcentration(checkDisplay);
            report.setSpanStandardConcentration(checkDisplay);

            Object resObj = root.get("result");
            if (resObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> resultList = (List<Map<String, String>>) resObj;
                report.setSpanDisplayResponses(resultList);
            } else {
                report.setSpanDisplayResponses(new ArrayList<>());
            }
            report.setSpanCalibrationResponse(checkDisplay);
        }
        report.setReportNote(String.join("\n", auditRemarkLines));

        List<Map<String, Object>> keyParameters = new ArrayList<>();
        appendKeySnapshotRows(keyParameters, records_0);
        if (keyParameters.isEmpty() && param != null && device != null) {
            Date[] readWin = QualityControlExecutionLogHelper.resolveReadPhaseWindowForKeyParameters(records_0.getExecutionLog());
            Instant kpStart = readWin != null ? readWin[0].toInstant() : records_0.getStartTime();
            Instant kpEnd = readWin != null ? readWin[1].toInstant()
                    : (records_0.getEndTime() != null ? records_0.getEndTime() : records_0.getStartTime());
            keyParameters = queryKeyParameters(kpStart, kpEnd, param, device.getId());
        }
        report.setKeyParameters(keyParameters);
        enrichKeyParameterRowsForReport(report.getKeyParameters(), report.getGasType());

        return report;
    }

    private static Number toNumber(Object o) {
        if (o instanceof Number) {
            return (Number) o;
        }
        if (o == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 任务设定核查浓度：params.genGasConc 按「CO 为 ppm、其余气态为 ppm 设定值→展示为 ppb」与站点任务一致。
     */
    private static String formatAuditSetpointDisplay(Number genGasConc, boolean coByParameterCode) {
        if (genGasConc == null) {
            return "";
        }
        double v = genGasConc.doubleValue();
        if (coByParameterCode) {
            return conciseConcNumber(v) + " ppm";
        }
        return conciseConcNumber(v * 1000.0) + " ppb";
    }

    private static String conciseConcNumber(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "";
        }
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * 核查读数：设备侧多为 ppb；CO 报表换算为 ppm。
     */
    private static String formatAuditInstrumentReadingDisplay(Object rawCheckData, boolean coByParameterCode) {
        if (rawCheckData == null) {
            return "";
        }
        try {
            double v = Double.parseDouble(String.valueOf(rawCheckData).trim());
            if (coByParameterCode) {
                return conciseConcNumber(v / 1000.0) + " ppm";
            }
            return conciseConcNumber(v) + " ppb";
        } catch (Exception e) {
            return String.valueOf(rawCheckData);
        }
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    @Override
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();

        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrumentInfo = new HashMap<>();
        instrumentInfo.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrumentInfo.put("instrument_name", report.getInstrumentName());
        instrumentInfo.put("instrument_no", report.getInstrumentNo());
        instrumentInfo.put("report_date", report.getReportDate().toString());
        instrumentInfo.put("gas_source_and_no", report.getGasSourceAndNo());
        instrumentInfo.put("gas_source", report.getGasSource());
        instrumentInfo.put("gas_no", report.getGasNo());
        instrumentInfo.put("gas_concentration", appendConcUnit(report.getGasConcentration(), report.getGasType()));
        reportContent.put("instrument_info", instrumentInfo);
        reportContent.put("start_time", report.getSpanStartTime());
        reportContent.put("end_time", report.getSpanEndTime());

        boolean coByParameter = CO.getCode().equals(report.getGasType());
        List<Map<String, Object>> calibrationPoints = new ArrayList<>();
        for (Map<String, String> auditCheckResultItem : report.getSpanDisplayResponses()) {
            calibrationPoints.add(
                    new LinkedHashMap<String, Object>() {{
                        put("point_name", "自定义标点");
                        put("check_concentration", report.getCheckConcentration());
                        put("check_time", auditCheckResultItem.get("checkTime"));
                        put("check_data", formatAuditInstrumentReadingDisplay(auditCheckResultItem.get("checkData"), coByParameter));
                        put("standard_concentration", report.getSpanStandardConcentration());
                        put("calibration_value", "");
                    }}
            );
        }
        reportContent.put("calibration_points", calibrationPoints);
        String gasCode = report.getGasType();
        String fullSpan = CO.getCode().equals(gasCode) ? FULL_SPAN_CO : FULL_SPAN;
        reportContent.put("full_span", fullSpan);
        reportContent.put("span_drift_result", report.getSpanDriftResult());
        reportContent.put("key_parameters", report.getKeyParameters());

        return reportContent;
    }
}
