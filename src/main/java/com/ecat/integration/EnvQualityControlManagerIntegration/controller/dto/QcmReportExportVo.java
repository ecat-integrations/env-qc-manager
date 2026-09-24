package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import com.ecat.core.Utils.DateTimeUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import com.ruoyi.common.annotation.Excel;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 质控报表导出 VO：report 导出曾直传实体（QcmReport 无 @Excel 注解）产出全空工作表
 * （bugs/bug-record-20260921-173000），改走与 QcmRecordExportVo 同款专用 VO。
 *
 * <p>列集轻量化：report_content/report_data/component 富内容与大字段不进导出；
 * 实体 Instant/LocalDate 列预格式化为 String（ecat 平台时区，与前端展示同源），实体保持纯净。
 * report_type/gas_type 落库为编码，导出时译为展示名，未知编码透传原值（不猜默认）。</p>
 *
 * @author coffee
 */
@Data
public class QcmReportExportVo {

    /** 导出时间列格式（与前端展示时区一致）。时区不在此固化：平台时区 volatile 可变（启动时从配置加载），
     * 格式化时动态挂 {@code DateTimeUtils.getZone()}；日期列为纯日期格式（LocalDate 与时区无关）。 */
    private static final DateTimeFormatter EXPORT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter EXPORT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Excel(name = "报表名称")
    private String reportName;

    @Excel(name = "报表类型")
    private String reportType;

    @Excel(name = "报表日期", width = 16)
    private String reportDate;

    @Excel(name = "仪器名称")
    private String instrumentName;

    @Excel(name = "仪器编号")
    private String instrumentNo;

    @Excel(name = "仪器类型")
    private String gasType;

    @Excel(name = "气源")
    private String gasSource;

    @Excel(name = "气瓶编号")
    private String gasNo;

    @Excel(name = "填表人")
    private String filer;

    @Excel(name = "审核人")
    private String reviewer;

    @Excel(name = "维护公司")
    private String maintenanceCompany;

    @Excel(name = "是否废弃")
    private String isDiscarded;

    @Excel(name = "创建人")
    private String createdBy;

    @Excel(name = "创建时间", width = 22)
    private String createTime;

    /** 实体 → 导出 VO 拷贝（时间预格式化；null 时间列输出空串，与旧空单元格行为一致）。 */
    public static QcmReportExportVo from(QcmReport r) {
        QcmReportExportVo vo = new QcmReportExportVo();
        vo.setReportName(r.getReportName());
        vo.setReportType(reportTypeDisplayName(r.getReportType()));
        vo.setReportDate(fmtDate(r.getReportDate()));
        vo.setInstrumentName(r.getInstrumentName());
        vo.setInstrumentNo(r.getInstrumentNo());
        vo.setGasType(gasTypeDisplayName(r.getGasType()));
        vo.setGasSource(r.getGasSource());
        vo.setGasNo(r.getGasNo());
        vo.setFiler(r.getFiler());
        vo.setReviewer(r.getReviewer());
        vo.setMaintenanceCompany(r.getMaintenanceCompany());
        vo.setIsDiscarded(discardText(r.getIsDiscarded()));
        vo.setCreatedBy(r.getCreatedBy());
        vo.setCreateTime(fmtTime(r.getCreateTime()));
        return vo;
    }

    /** 报表类型编码 → 展示名；未知编码透传原值。 */
    private static String reportTypeDisplayName(String reportType) {
        if (reportType == null || reportType.isEmpty()) {
            return reportType;
        }
        ReportTypeEnum e = ReportTypeEnum.findByCode(reportType);
        return e != null ? e.getDisplayName() : reportType;
    }

    /** 仪器类型编码（值域同 ParameterEnum，qcm_report.gas_type 存编码）→ 化学符号/展示名；未知值透传。 */
    private static String gasTypeDisplayName(String gasType) {
        if (gasType == null || gasType.isEmpty()) {
            return gasType;
        }
        String name = ParameterEnum.getNameByCode(gasType);
        return name != null ? name : gasType;
    }

    /** 是否废弃 Boolean → 是/否；null 输出空串。 */
    private static String discardText(Boolean isDiscarded) {
        if (isDiscarded == null) {
            return "";
        }
        return isDiscarded ? "是" : "否";
    }

    private static String fmtTime(Instant time) {
        return time != null ? EXPORT_TIME_FORMATTER.withZone(DateTimeUtils.getZone()).format(time) : "";
    }

    private static String fmtDate(LocalDate date) {
        return date != null ? EXPORT_DATE_FORMATTER.format(date) : "";
    }

    /** 批量拷贝。 */
    public static List<QcmReportExportVo> fromList(List<QcmReport> reports) {
        List<QcmReportExportVo> vos = new ArrayList<>(reports.size());
        for (QcmReport r : reports) {
            vos.add(from(r));
        }
        return vos;
    }
}
