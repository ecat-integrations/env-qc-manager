package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import lombok.Data;

import java.util.HashMap;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;

/**
 * AuditSpanReport
 * 用于生成 人工核查 记录表
 *
 * @author coffee
 */
@Data
public class AuditSpanReport extends QcmReport {
    // 报告标题
    private String reportName = "人工核查记录表";
    // 报告类型
    private String reportType = ReportTypeEnum.AUDIT_SPAN.getCode();
    // 报告类型中文
    private String reportDisplayType = ReportTypeEnum.AUDIT_SPAN.getDisplayName();
    // 报表组件
    private String component = ReportTypeEnum.AUDIT_SPAN.getComponent();
    // 报表内容，需要组装
    private Map<String, Object> reportData = new HashMap<>();
    // 报表内容，用于数据库存储
    private String reportContent = "";
    // 校准日期
    private LocalDate reportDate;
    // 设备名称及编号
    private String instrumentNameAndNo = "";
    // 标气来源及编号
    private String gasSourceAndNo = "";
    // 标气浓度
    private String gasConcentration = "";
    // 使用满量程
    private String fullSpan = "";

    // 开始时间
    private String spanStartTime = "";
    // 结束时间
    private String spanEndTime = "";
    // 标准浓度
    private String spanStandardConcentration = "";
    // 核查浓度
    private String checkConcentration = "";
    // 显示值
    private List<Map<String, String>> spanDisplayResponses = new ArrayList<>();
    // 标定值
    private String spanCalibrationResponse = "";
    // 漂移
    private String spanDriftResult = "";

    // 关键参数
    private List< Map<String, Object> > keyParameters = new ArrayList<>();
    // 备注
    private String reportNote = "";
    // 填表人
    private String filer = "";
    // 复核人
    private String reviewer = "";

}
