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
 * PrecisionReport
 * 用于生成 xxx仪器精密度审核记录表
 */
@Data
public class PrecisionReport extends QcmReport {
    // 标题
    private String reportName = "仪器精密度审核记录表";
    // 报告类型
    private String reportType = ReportTypeEnum.PRECISION.getCode();
    // 报告类型中文
    private String reportDisplayType = ReportTypeEnum.PRECISION.getDisplayName();
    // 报表组件
    private String component = ReportTypeEnum.PRECISION.getComponent();
    // 报表内容，需要组装
    private Map<String, Object> reportData = new HashMap<>();
    // 报表内容，用于数据库存储
    private String reportContent = "";
    // 审核日期
    private LocalDate reportDate;
    // 设备名称及编号
    private String instrumentNameAndNo = "";
    // 标气来源及编号
    private String gasSourceAndNo = "";
    // 标气浓度
    private String gasConcentration = "";
    // 通入仪器的标气浓度
    private String gasConcentrationsInput = "";
    // 仪器响应值
    private List<Float> instrumentResponses = new ArrayList<>(6);
    // 相对标准偏差
    private Float relativeStandardDeviation;
    // 执行日志中的通入仪器标气浓度
    private Float devicesStdGasFromMetrics;
    // 校准结果
    private String calibrationResult = "";
    // 备注
    private String reportNote = "";
    // 填表人
    private String filer = "";
    // 复核人
    private String reviewer = "";
}
