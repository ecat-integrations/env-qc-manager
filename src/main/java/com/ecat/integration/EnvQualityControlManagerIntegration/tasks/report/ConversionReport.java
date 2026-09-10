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
 * ConversionReport
 * 用于生成 xxx分析仪转换效率测试记录表
 * 目前就是 氮氧化物分析仪转换效率测试记录表
 *
 * @author coffee
 */
@Data
public class ConversionReport extends QcmReport {
    // 标题
    private String reportName = "分析仪转换效率测试记录表";
    // 报告类型
    private String reportType = ReportTypeEnum.CONVERSION.getCode();
    // 报告类型中文
    private String reportDisplayType = ReportTypeEnum.CONVERSION.getDisplayName();
    // 报表组件
    private String component = ReportTypeEnum.CONVERSION.getComponent();
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
    // 标气浓度 [NO2, NO]
    private List<String> gasConcentration = new ArrayList<String> (2);
    // 使用NO2标气进行转换效率测试 氮氧化物分析仪读数 [ 第一次，第二次，第三次]
    private List<Float> origNo2Datas = new ArrayList<Float>(3);
    // 均值
    private Float origNo2Avg;
    // 转换效率
    private Float no2Efficiency;
    // 使用NO标气进行转换效率测试 氮氧化物分析仪读数 [ 第一次，第二次，第三次]
    // 关O3 - NO
    private List<Float> origNoDatas = new ArrayList<Float>(3);
    // 关O3 - NOx
    private List<Float> origNoxDatas = new ArrayList<Float>(3);
    // 开O3 - NO
    private List<Float> remNoDatas = new ArrayList<Float>(3);
    // 开O3 - NOx
    private List<Float> remNoxDatas = new ArrayList<Float>(3);
    // 均值
    private Float origNoAvg;
    private Float origNoxAvg;
    private Float remNoAvg;
    private Float remNoxAvg;
    // 转换效率
    private String noEfficiency = "";
    // 校准结果
    private String calibrationResult = "";
    // 备注
    private String reportNote = "";
    // 填表人
    private String filer = "";
    // 复核人
    private String reviewer = "";

}
