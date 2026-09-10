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
 * AccuracyReport
 * 用于生成 xxx仪器准确度审核记录表
 *
 * @author coffee
 */
@Data
public class AccuracyReport extends QcmReport {
    // 标题
    private String reportName = "仪器准确度审核记录表";
    // 报告类型
    private String reportType = ReportTypeEnum.ACCURACY.getCode();
    // 报告类型
    private String reportDisplayType = ReportTypeEnum.ACCURACY.getDisplayName();
    // 报表组件
    private String component = ReportTypeEnum.ACCURACY.getComponent();
    // 报表内容，需要组装
    private Map<String, Object> reportData = new HashMap<>();
    // 报表内容，用于数据库存储
    private String reportContent = "";
    // 审核日期
    private LocalDate reportDate;
    // 设备名称及编号
    private String instrumentNameAndNo = "";
    // 标气来源及编号
    private String gasSourceAndNo = this.getGasType() + " " + this.getInstrumentNo();
    // 标气浓度
    private String gasConcentration = "";
    // 通入仪器的标气浓度
    private List<Float> gasConcentrationsInput = new ArrayList<>(6);
    // 仪器响应值
    private List<Float> instrumentResponses = new ArrayList<>(6);
    // 仪器平均相对误差
    private String averageRelativeError = "";
    // 多点校准曲线
    private String formula = "";
    // 斜率
    private String a = "";
    // 截距
    private String b = "";
    // 相关系数
    private String r = "";
    // 校准结果
    private String calibrationResult = "";
    // 备注
    private String reportNote = "";
    // 填表人
    private String filer = "";
    // 复核人
    private String reviewer = "";
}
