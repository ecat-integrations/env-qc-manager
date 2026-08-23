package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.firstNonBlank;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.recordCreatorRef;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.recordUpdaterRef;

/**
 * GenTransferAndTraceReport
 * <p>生成臭氧校准设备量值传递记录表</p>
 * @author caohongbo
 * @version 1.0
 */
public class GenTransferAndTraceReport extends ReportGenerator {

    private final EcatCore core;

    @Getter
    private TransferAndTraceReport report;

    public GenTransferAndTraceReport(EcatCore core, QcmRecord record) {
        this(core, record, null);
    }

    /** AC-C8：传入 generate() 预取的参数级设备缓存（可 null=逐条查询）。 */
    public GenTransferAndTraceReport(EcatCore core, QcmRecord record, Map<String, DeviceBase> devicePrefetch) {
        super(core, devicePrefetch);
        this.core = core;
        report = new TransferAndTraceReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.CALIBRATION.getComponent());
        attachReportData(report);
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private TransferAndTraceReport parseRecordToReport(QcmRecord record) {
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
        report.setGasSource(param);
        applyStandardGasSourceAndNo(report, param, record);
        refreshReportGasSourceAndNoCombined(report);
        DeviceBase device = getDeviceInfo(param);
        report.setInstrumentName(device.getName());
        report.setInstrumentNo(device.getSn());
        report.setInstrumentNameAndNo(device.getName() + device.getSn());
        report.setReportName(device.getName() + report.getReportName());

        return report;
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

        return reportContent;
    }
}
