package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.appendConcUnit;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.convertToFloat;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.convertToFloatList;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.ensurePercentSuffix;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.firstNonBlank;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.formatFloatListWithConcUnit;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.formatRelativeStandardDeviationPercent;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.metricString;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.recordCreatorRef;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.recordUpdaterRef;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.stripNumericConcentration;

/**
 * GenAccuracyReport
 * <p>生成仪器准确度审核记录表</p>
 * @author caohongbo
 * @version 1.0
 */
public class GenAccuracyReport extends ReportGenerator {

    private final EcatCore core;

    private final QcmRecord qcRecord;

    @Getter
    private AccuracyReport report;

    public GenAccuracyReport(EcatCore core, QcmRecord record) {
        this(core, record, null);
    }

    /** AC-C8：传入 generate() 预取的参数级设备缓存（可 null=逐条查询）。 */
    public GenAccuracyReport(EcatCore core, QcmRecord record, Map<String, DeviceBase> devicePrefetch) {
        super(core, devicePrefetch);
        this.core = core;
        this.qcRecord = record;
        report = new AccuracyReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.ACCURACY.getComponent());
        attachReportData(report);
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private AccuracyReport parseRecordToReport(QcmRecord record) {
        report.setReportDate(ReportFormatSupport.reportDateOf(record.getStartTime()));
        String filerRef = recordCreatorRef(record);
        String reviewerRef = firstNonBlank(recordUpdaterRef(record), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? record.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(record.getUpdatedBy()) : reviewerRef);
        report.setReportNote(record.getResultEvaluation());
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param != null ? param : "");
        if (param != null) {
            applyStandardGasSourceAndNo(report, param, record);
            refreshReportGasSourceAndNoCombined(report);
        }
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
        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = QualityControlExecutionLogHelper.metricsForReport(executionLog);
        report.setFormula("Y = aX + b");
        report.setA(metricString(executionLogMap, "slope"));
        report.setB(metricString(executionLogMap, "intercept"));
        report.setR(metricString(executionLogMap, "correlation"));
        report.setAverageRelativeError(metricString(executionLogMap, "relativeError"));

        List<Float> gasConcentrationInput = convertToFloatList(executionLogMap.get("stdValues"));
        report.setGasConcentrationsInput(gasConcentrationInput);
        List<Float> instrumentResponse = convertToFloatList(executionLogMap.get("deviceValues"));
        report.setInstrumentResponses(instrumentResponse);

        String cylinderConc = param != null ? getStdGasConcentration(param) : "";
        report.setGasConcentration(cylinderConc == null ? "" : cylinderConc);

        boolean pass = QualityControlExecutionLogHelper.readBoolean(executionLogMap, "isPass");
        report.setCalibrationResult(pass ? "合格" : "不合格");

        return report;
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

        String gasCode = report.getGasType();
        Map<String, String> instrumentInfo = new HashMap<>();
        instrumentInfo.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrumentInfo.put("instrument_name", report.getInstrumentName());
        instrumentInfo.put("instrument_no", report.getInstrumentNo());
        instrumentInfo.put("report_date", report.getReportDate().toString());
        instrumentInfo.put("gas_source_and_no", report.getGasSourceAndNo());
        instrumentInfo.put("gas_source", report.getGasSource());
        instrumentInfo.put("gas_no", report.getGasNo());
        instrumentInfo.put("gas_concentration", appendConcUnit(stripNumericConcentration(report.getGasConcentration()), gasCode));
        boolean pass = qcRecord != null && QualityControlExecutionLogHelper.readIsPass(qcRecord.getExecutionLog());
        instrumentInfo.put("qc_stamp", pass ? "pass" : "fail");
        reportContent.put("instrument_info", instrumentInfo);

        reportContent.put("gas_concentrations_input", formatFloatListWithConcUnit(report.getGasConcentrationsInput(), gasCode));
        reportContent.put("instrument_responses", formatFloatListWithConcUnit(report.getInstrumentResponses(), gasCode));

        Map<String, String> calibrationCurve = new HashMap<>();
        calibrationCurve.put("formula", report.getFormula());
        calibrationCurve.put("a", report.getA());
        calibrationCurve.put("b", report.getB());
        calibrationCurve.put("r", report.getR());
        reportContent.put("calibration_curve", calibrationCurve);

        Float relF = convertToFloat(report.getAverageRelativeError());
        String relDisp = relF != null
                ? formatRelativeStandardDeviationPercent(relF)
                : ensurePercentSuffix(report.getAverageRelativeError());
        reportContent.put("average_relative_error", relDisp);
        reportContent.put("calibration_result", report.getCalibrationResult());
        reportContent.put("audit_result", report.getCalibrationResult());
        reportContent.put("remark", report.getReportNote() != null ? report.getReportNote() : "");
        reportContent.put("filer", report.getFiler() != null ? report.getFiler() : "");
        reportContent.put("reviewer", report.getReviewer() != null ? report.getReviewer() : "");

        return reportContent;
    }
}
