package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.appendConcUnit;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.convertToFloat;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.convertToFloatList;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.ensurePercentSuffix;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.firstNonNullMetric;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.firstNonBlank;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.formatEfficiencyPercent;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.formatFloatListWithConcUnit;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.recordCreatorRef;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.recordUpdaterRef;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.stripNumericConcentration;

/**
 * GenConversionReport
 * <p>生成氮氧化物转换效率测试记录表</p>
 * @author caohongbo
 * @version 1.0
 */
public class GenConversionReport extends ReportGenerator {

    private final EcatCore core;

    private final QcmRecord qcRecord;

    @Getter
    private ConversionReport report;

    public GenConversionReport(EcatCore core, QcmRecord record) {
        this(core, record, null);
    }

    /** AC-C8：传入 generate() 预取的参数级设备缓存（可 null=逐条查询）。 */
    public GenConversionReport(EcatCore core, QcmRecord record, Map<String, DeviceBase> devicePrefetch) {
        super(core, devicePrefetch);
        this.core = core;
        this.qcRecord = record;
        report = new ConversionReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.CONVERSION.getComponent());
        attachReportData(report);
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private ConversionReport parseRecordToReport(QcmRecord record) {
        report.setReportDate(ReportFormatSupport.reportDateOf(record.getStartTime())); // 报告日期 默认是质控记录开始时间
        String filerRef = recordCreatorRef(record);
        String reviewerRef = firstNonBlank(recordUpdaterRef(record), filerRef);
        report.setFiler(resolveReportFilerDisplayName(filerRef));
        report.setReviewer(resolveReportPersonDisplayName(reviewerRef));
        report.setCreatedBy(filerRef.isEmpty() ? record.getCreatedBy() : filerRef);
        report.setUpdatedBy(reviewerRef.isEmpty() ? firstNonBlank(record.getUpdatedBy()) : reviewerRef);
        report.setReportNote(record.getResultEvaluation()); // 备注 默认是质控记录结果评价
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

        String concUnitCode = ParameterEnum.NO2.getCode();
        List<String> gasConcentrations = new ArrayList<>(2);
        String noC = getStdGasConcentration("NO");
        String no2C = getStdGasConcentration("NO2");
        gasConcentrations.add(noC == null ? "" : appendConcUnit(stripNumericConcentration(noC), concUnitCode));
        gasConcentrations.add(no2C == null ? "" : appendConcUnit(stripNumericConcentration(no2C), concUnitCode));
        report.setGasConcentration(gasConcentrations);

        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = QualityControlExecutionLogHelper.metricsForReport(executionLog);

        report.setOrigNo2Datas(convertToFloatList(firstNonNullMetric(executionLogMap, "origNo2Datas", "remNo2Datas")));
        report.setOrigNo2Avg(convertToFloat(firstNonNullMetric(executionLogMap, "origNo2Avg", "remNo2Avg")));
        report.setNo2Efficiency(convertToFloat(firstNonNullMetric(executionLogMap, "no2Efficiency", "efficiency2")));

        report.setRemNoDatas(convertToFloatList(firstNonNullMetric(executionLogMap, "remNoDatas")));
        report.setRemNoxDatas(convertToFloatList(firstNonNullMetric(executionLogMap, "remNoxDatas")));
        report.setOrigNoDatas(convertToFloatList(firstNonNullMetric(executionLogMap, "origNoDatas")));
        report.setOrigNoxDatas(convertToFloatList(firstNonNullMetric(executionLogMap, "origNoxDatas")));
        report.setRemNoAvg(convertToFloat(firstNonNullMetric(executionLogMap, "remNoAvg")));
        report.setRemNoxAvg(convertToFloat(firstNonNullMetric(executionLogMap, "remNoxAvg")));
        report.setOrigNoAvg(convertToFloat(firstNonNullMetric(executionLogMap, "origNoAvg")));
        report.setOrigNoxAvg(convertToFloat(firstNonNullMetric(executionLogMap, "origNoxAvg")));

        Object effObj = firstNonNullMetric(executionLogMap, "efficiency", "conversionEfficiency");
        /* NO 路径转换效率：与入库 execution_log.result 中主字段一致（编排器写入后原样持久化）。 */
        report.setNoEfficiency(effObj == null ? "" : String.valueOf(effObj));

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

        boolean pass = qcRecord != null && QualityControlExecutionLogHelper.readIsPass(qcRecord.getExecutionLog());
        reportContent.put("is_pass", pass);
        reportContent.put("calibration_result", report.getCalibrationResult());

        String concUnitCode = ParameterEnum.NO2.getCode();

        Map<String, String> instrumentInfo = new HashMap<>();
        instrumentInfo.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrumentInfo.put("instrument_name", report.getInstrumentName());
        instrumentInfo.put("instrument_no", report.getInstrumentNo());
        instrumentInfo.put("report_date", report.getReportDate().toString());
        instrumentInfo.put("gas_source_and_no", report.getGasSourceAndNo());
        instrumentInfo.put("gas_source", report.getGasSource());
        instrumentInfo.put("gas_no", report.getGasNo());
        instrumentInfo.put("qc_stamp", pass ? "pass" : "fail");
        if (report.getGasConcentration() != null && report.getGasConcentration().size() > 1) {
            instrumentInfo.put("no_concentration", report.getGasConcentration().get(0));
            instrumentInfo.put("no2_concentration", report.getGasConcentration().get(1));
        } else if (report.getGasConcentration() != null && report.getGasConcentration().size() > 0) {
            instrumentInfo.put("no_concentration", report.getGasConcentration().get(0));
            instrumentInfo.put("no2_concentration", "");
        } else {
            instrumentInfo.put("no_concentration", "");
            instrumentInfo.put("no2_concentration", "");
        }
        reportContent.put("instrument_info", instrumentInfo);

        reportContent.put("orig_no2_datas", formatFloatListWithConcUnit(report.getOrigNo2Datas(), concUnitCode));
        reportContent.put("orig_no2_avg", formatSingleConcForReport(report.getOrigNo2Avg(), concUnitCode));
        reportContent.put("no2_efficiency", formatEfficiencyPercent(report.getNo2Efficiency()));

        reportContent.put("rem_no_datas", formatFloatListWithConcUnit(report.getRemNoDatas(), concUnitCode));
        reportContent.put("rem_no_avg", formatSingleConcForReport(report.getRemNoAvg(), concUnitCode));
        reportContent.put("rem_nox_datas", formatFloatListWithConcUnit(report.getRemNoxDatas(), concUnitCode));
        reportContent.put("rem_nox_avg", formatSingleConcForReport(report.getRemNoxAvg(), concUnitCode));
        reportContent.put("orig_no_datas", formatFloatListWithConcUnit(report.getOrigNoDatas(), concUnitCode));
        reportContent.put("orig_no_avg", formatSingleConcForReport(report.getOrigNoAvg(), concUnitCode));
        reportContent.put("orig_nox_datas", formatFloatListWithConcUnit(report.getOrigNoxDatas(), concUnitCode));
        reportContent.put("orig_nox_avg", formatSingleConcForReport(report.getOrigNoxAvg(), concUnitCode));
        reportContent.put("efficiency", ensurePercentSuffix(report.getNoEfficiency()));
        reportContent.put("result_evaluation", report.getReportNote() != null ? report.getReportNote() : "");
        reportContent.put("filer", report.getFiler() != null ? report.getFiler() : "");
        reportContent.put("reviewer", report.getReviewer() != null ? report.getReviewer() : "");

        return reportContent;
    }

    private static String formatSingleConcForReport(Float f, String gasTypeCode) {
        if (f == null) {
            return "";
        }
        return appendConcUnit(String.valueOf(f), gasTypeCode);
    }
}
