package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.appendConcUnit;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.appendDriftUnit;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.buildZeroSpanReportRemark;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.enrichKeyParameterRowsForReport;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.firstReadPhaseWindowForKeyParameters;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.fmtReportTime;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.formatCalibrationResponse;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.formatSpanDriftPercentDisplay;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.isCoReportGas;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.mergeKeyParameterSnapshotsFromRecords;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.pickVerificationValue;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.SPAN_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.ZERO_CHECK;

/**
 * GenZeroAndSpanReport
 * <p>生成仪器运行状况检查/校准记录表</p>
 * 生成零点及跨度检测报表：择优后的零点+跨度记录各至多一条，聚为单日单表。
 *
 * @author caohongbo
 * @version 1.0
 */
public class GenZeroAndSpanReport extends ReportGenerator {

    private final EcatCore core;

    private final LocalDate businessDay;

    @Getter
    private ZeroAndSpanReport report;

    public static final String FULL_SPAN = "500ppb";
    public static final String FULL_SPAN_CO = "50ppm";

    /**
     * @param businessDay   监管日（与记录 start_time 在同一时区下的日历日一致）
     * @param chosenRecords 择优后的 1～2 条记录（零点、跨度各至多一条）
     */
    public GenZeroAndSpanReport(EcatCore core, LocalDate businessDay, List<QcmRecord> chosenRecords) {
        this(core, businessDay, chosenRecords, null);
    }

    /** AC-C8：传入 generate() 预取的参数级设备缓存（可 null=逐条查询）。 */
    public GenZeroAndSpanReport(EcatCore core, LocalDate businessDay, List<QcmRecord> chosenRecords,
                                Map<String, DeviceBase> devicePrefetch) {
        super(core, devicePrefetch);
        this.core = core;
        this.businessDay = businessDay;
        report = new ZeroAndSpanReport();
        report = parseRecordToReport(chosenRecords);
        report.setComponent(ReportTypeEnum.ZERO_SPAN.getComponent());
        attachReportData(report);
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param chosenRecords 择优后的记录列表
     * @return report 质控报告
     */
    private ZeroAndSpanReport parseRecordToReport(List<QcmRecord> chosenRecords) {
        QcmRecord zero = null;
        QcmRecord span = null;
        for (QcmRecord r : chosenRecords) {
            if (ZERO_CHECK.getCode().equals(r.getQualityControlType())) {
                zero = r;
            } else if (SPAN_CHECK.getCode().equals(r.getQualityControlType())) {
                span = r;
            }
        }
        QcmRecord anchor = zero != null ? zero : span;
        if (anchor == null) {
            throw new IllegalArgumentException("Zero/span report requires at least one record");
        }

        report.setReportDate(businessDay);
        applyReportFilerAndEmptyReviewer(report, anchor, zero, span);
        report.setGasType(anchor.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        if (param == null) {
            logger.warn("Zero/span report: unknown parameter code {}, skip instrument / std gas resolution", report.getGasType());
        }
        report.setGasSource(param != null ? param : "");
        if (param != null) {
            applyStandardGasSourceAndNo(report, param, anchor);
            refreshReportGasSourceAndNoCombined(report);
        }
        DeviceBase device = param != null ? getDeviceInfo(param) : null;
        if (device != null) {
            report.setInstrumentName(device.getName() != null ? device.getName() : "");
            report.setReportName(device.getName() + report.getReportName());
            report.setInstrumentNo(device.getSn() != null ? device.getSn() : "");
            report.setInstrumentNameAndNo(
                    (device.getName() != null ? device.getName() : "") + (device.getSn() != null ? device.getSn() : ""));
        } else {
            report.setInstrumentName("");
            report.setReportName(report.getReportName());
            report.setInstrumentNo("");
            report.setInstrumentNameAndNo("");
        }
        String gasConcentration = param != null ? resolveReportStdGasConcentration(param, zero, span) : "";
        report.setGasConcentration(gasConcentration);

        if (zero != null) {
            report.setZeroStartTime(fmtTime(zero.getStartTime()));
            report.setZeroEndTime(fmtTime(zero.getEndTime() != null ? zero.getEndTime() : zero.getStartTime()));
            Map<String, Object> executionLogMap = QualityControlExecutionLogHelper.metricsForReport(zero.getExecutionLog());
            applyZeroMetrics(executionLogMap, zero.getParameter());
            boolean zp = QualityControlExecutionLogHelper.readBoolean(executionLogMap, "isPass");
            report.setZeroCalibrationResult(zp ? "合格" : "不合格");
        }
        if (span != null) {
            report.setSpan80StartTime(fmtTime(span.getStartTime()));
            report.setSpan80EndTime(fmtTime(span.getEndTime() != null ? span.getEndTime() : span.getStartTime()));
            Map<String, Object> resultEvaluation = QualityControlExecutionLogHelper.metricsForReport(span.getExecutionLog());
            applySpanMetrics(resultEvaluation, span.getParameter());
            boolean sp = QualityControlExecutionLogHelper.readBoolean(resultEvaluation, "isPass");
            report.setSpanCalibrationResult(sp ? "合格" : "不合格");
        }
        report.setReportNote(buildZeroSpanReportRemark(zero, span));

        Instant kpStart = null;
        Instant kpEnd = null;
        for (QcmRecord r : new QcmRecord[]{zero, span}) {
            if (r == null) {
                continue;
            }
            Instant s = r.getStartTime();
            Instant e = r.getEndTime() != null ? r.getEndTime() : r.getStartTime();
            if (s != null && (kpStart == null || s.isBefore(kpStart))) {
                kpStart = s;
            }
            if (e != null && (kpEnd == null || e.isAfter(kpEnd))) {
                kpEnd = e;
            }
        }
        if (kpStart == null) {
            kpStart = anchor.getStartTime();
        }
        if (kpEnd == null) {
            kpEnd = anchor.getEndTime() != null ? anchor.getEndTime() : anchor.getStartTime();
        }
        Instant[] readWin = firstReadPhaseWindowForKeyParameters(zero, span);
        if (readWin != null) {
            kpStart = readWin[0];
            kpEnd = readWin[1];
        }
        List<Map<String, Object>> keyParameters = mergeKeyParameterSnapshotsFromRecords(zero, span);
        if (keyParameters.isEmpty() && param != null && device != null) {
            keyParameters = queryKeyParameters(kpStart, kpEnd, param, device.getId());
        }
        report.setKeyParameters(keyParameters);
        enrichKeyParameterRowsForReport(report.getKeyParameters(), report.getGasType());
        report.setZeroCalibrationValueApplicable(
                zero != null && QualityControlExecutionLogHelper.hasCompletedCalibrationPhase(zero.getExecutionLog()));
        report.setSpanCalibrationValueApplicable(
                span != null && QualityControlExecutionLogHelper.hasCompletedCalibrationPhase(span.getExecutionLog()));

        return report;
    }

    private String fmtTime(Instant d) {
        return fmtReportTime(d);
    }

    private void applyZeroMetrics(Map<String, Object> executionLogMap, String parameterCode) {
        if (executionLogMap == null || executionLogMap.isEmpty()) {
            return;
        }
        Object calSrc = pickVerificationValue(executionLogMap);
        if (isCoReportGas(parameterCode)) {
            try {
                report.setZeroStandardConcentration(((Number) executionLogMap.get("stdValue")).doubleValue() / 1000 + "");
                report.setZeroDisplayResponse(((Number) executionLogMap.get("deviceValue")).doubleValue() / 1000 + "");
                report.setZeroCalibrationResponse(formatCalibrationResponse(calSrc, parameterCode));
                report.setZeroDriftResult(((Number) executionLogMap.get("resultValue")).doubleValue() / 1000 + "");
            } catch (Exception e) {
                report.setZeroStandardConcentration(String.valueOf(executionLogMap.get("stdValue")));
                report.setZeroDisplayResponse(String.valueOf(executionLogMap.get("deviceValue")));
                report.setZeroCalibrationResponse(formatCalibrationResponse(calSrc, parameterCode));
                report.setZeroDriftResult(String.valueOf(executionLogMap.get("resultValue")));
            }
        } else {
            report.setZeroStandardConcentration(String.valueOf(executionLogMap.get("stdValue")));
            report.setZeroDisplayResponse(String.valueOf(executionLogMap.get("deviceValue")));
            report.setZeroCalibrationResponse(formatCalibrationResponse(calSrc, parameterCode));
            report.setZeroDriftResult(String.valueOf(executionLogMap.get("resultValue")));
        }
    }

    private void applySpanMetrics(Map<String, Object> resultEvaluation, String parameterCode) {
        if (resultEvaluation == null || resultEvaluation.isEmpty()) {
            return;
        }
        report.setSpan80DriftResult(String.valueOf(resultEvaluation.get("resultValue")));
        Object calSrc = pickVerificationValue(resultEvaluation);
        if (isCoReportGas(parameterCode)) {
            try {
                report.setSpan80StandardConcentration(((Number) resultEvaluation.get("stdValue")).doubleValue() / 1000 + "");
                report.setSpan80DisplayResponse(((Number) resultEvaluation.get("deviceValue")).doubleValue() / 1000 + "");
                report.setSpan80CalibrationResponse(formatCalibrationResponse(calSrc, parameterCode));
            } catch (Exception e) {
                report.setSpan80StandardConcentration(String.valueOf(resultEvaluation.get("stdValue")));
                report.setSpan80DisplayResponse(String.valueOf(resultEvaluation.get("deviceValue")));
                report.setSpan80CalibrationResponse(formatCalibrationResponse(calSrc, parameterCode));
            }
        } else {
            report.setSpan80StandardConcentration(String.valueOf(resultEvaluation.get("stdValue")));
            report.setSpan80DisplayResponse(String.valueOf(resultEvaluation.get("deviceValue")));
            report.setSpan80CalibrationResponse(formatCalibrationResponse(calSrc, parameterCode));
        }
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    @Override
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();

        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrumentInfo = new HashMap<>();
        // 设备名称及编号
        instrumentInfo.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrumentInfo.put("instrument_name", report.getInstrumentName());
        instrumentInfo.put("instrument_no", report.getInstrumentNo());
        // 校准日期
        LocalDate reportDate = report.getReportDate();
        instrumentInfo.put("report_date", reportDate != null ? reportDate.toString() : "");
        // 标气来源及编号
        instrumentInfo.put("gas_source_and_no", report.getGasSourceAndNo());
        instrumentInfo.put("gas_source", report.getGasSource());
        instrumentInfo.put("gas_no", report.getGasNo());
        // 标气浓度
        instrumentInfo.put("gas_concentration", appendConcUnit(report.getGasConcentration(), report.getGasType()));
        boolean hasZero = report.getZeroStartTime() != null && !report.getZeroStartTime().trim().isEmpty();
        boolean hasSpan = report.getSpan80StartTime() != null && !report.getSpan80StartTime().trim().isEmpty();
        boolean passOverall = true;
        if (hasZero) {
            passOverall &= "合格".equals(report.getZeroCalibrationResult());
        }
        if (hasSpan) {
            passOverall &= "合格".equals(report.getSpanCalibrationResult());
        }
        instrumentInfo.put("qc_stamp", passOverall ? "pass" : "fail");
        reportContent.put("instrument_info", instrumentInfo);
        // 零点和跨度结果
        List<Map<String, Object>> calibrationPoints = new ArrayList<>();
        final boolean zeroApplicable = report.isZeroCalibrationValueApplicable();
        final boolean spanApplicable = report.isSpanCalibrationValueApplicable();
        calibrationPoints.add(
                new LinkedHashMap<String, Object>() {{
                    put("point_name", "零点");
                    put("start_time", report.getZeroStartTime());
                    put("end_time", report.getZeroEndTime());
                    put("standard_concentration", appendConcUnit(report.getZeroStandardConcentration(), report.getGasType()));
                    put("display_value", appendConcUnit(report.getZeroDisplayResponse(), report.getGasType()));
                    put("calibration_value", zeroApplicable
                            ? appendConcUnit(report.getZeroCalibrationResponse(), report.getGasType()) : "");
                }}
        );
        calibrationPoints.add(
                new LinkedHashMap<String, Object>() {{
                    put("point_name", "满量程的80%");
                    put("start_time", report.getSpan80StartTime());
                    put("end_time", report.getSpan80EndTime());
                    put("standard_concentration", appendConcUnit(report.getSpan80StandardConcentration(), report.getGasType()));
                    put("display_value", appendConcUnit(report.getSpan80DisplayResponse(), report.getGasType()));
                    put("calibration_value", spanApplicable
                            ? appendConcUnit(report.getSpan80CalibrationResponse(), report.getGasType()) : "");
                }}
        );
        reportContent.put("calibration_points", calibrationPoints);
        String gasCode = report.getGasType();
        String fullSpan = isCoReportGas(gasCode) ? FULL_SPAN_CO : FULL_SPAN;
        reportContent.put("full_span", fullSpan);
        String driftUnit = isCoReportGas(gasCode) ? " ppm" : " ppb";
        reportContent.put("zero_drift_result", appendDriftUnit(report.getZeroDriftResult(), driftUnit));
        reportContent.put("span_80_drift_result", formatSpanDriftPercentDisplay(report.getSpan80DriftResult()));
        reportContent.put("key_parameters", report.getKeyParameters());
        reportContent.put("span_calibration_result", report.getSpanCalibrationResult());
        reportContent.put("zero_calibration_result", report.getZeroCalibrationResult());
        reportContent.put("remark", report.getReportNote() != null ? report.getReportNote() : "");
        reportContent.put("filer", report.getFiler() != null ? report.getFiler() : "");
        reportContent.put("reviewer", report.getReviewer() != null ? report.getReviewer() : "");

        return reportContent;
    }
}
