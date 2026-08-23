package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import lombok.Data;

import java.util.HashMap;

import java.util.Map;
import java.time.LocalDate;

/**
 * TransferAndTraceReport
 * 用于生成 xxx校准设备量值传递与溯源记录表
 * 目前就是 臭氧校准设备量值传递记录表
 */
@Data
public class TransferAndTraceReport extends QcmReport {
    // 标题
    private String reportName = "校准设备量值传递记录表";
    // 报告类型
    private String reportType = ReportTypeEnum.CALIBRATION.getCode();  //前端写的是校准
    // 报告类型中文
    private String reportDisplayType = ReportTypeEnum.CALIBRATION.getDisplayName();
    // 报表组件
    private String component = ReportTypeEnum.CALIBRATION.getComponent();
    // 报表内容，需要组装
    private Map<String, Object> reportData = new HashMap<>();
    // 报表内容，用于数据库存储
    private String reportContent = "";
    // 审核日期
    private LocalDate reportDate;
    // 设备名称和编号
    private String instrumentNameAndNo;



}
