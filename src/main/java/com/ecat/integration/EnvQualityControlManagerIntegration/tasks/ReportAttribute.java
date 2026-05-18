package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import lombok.Data;

import java.util.*;

/**
 * Reports
 * <p>记录各个报告生成需要的参数</p>
 * @author caohongbo
 * @version 1.0
 * @description
 * <pre>继承基础质控报告类并扩展字段
 */

public class ReportAttribute {
    // 根据质控类型返回对应的报表对象
    public static EnvQualityControlReport getReport(String reportType) {

        switch (reportType) {
            // 零点或跨度，生成零跨报表
            case "zero_span":
                return new ZeroAndSpanReport();
            case "multi":
                return new MultiCheckReport();
            case "precision":
                return new PrecisionReport();
            case "accuracy":
                return new AccuracyReport();
            case "conversion":
                return new ConversionReport();
            case "calibration":
                return new TransferAndTraceReport();
            case "audit_span":
                return new AuditSpanReport();
        }
        throw new RuntimeException("Invalid qualityControlType: " + reportType);
    }
}


////////////////////////////////////////////////////////////////////////////////
/**
 * ZeroAndSpanReport
 * 用于生成 xxx仪器运行状况检查记录表
 */
@Data
class ZeroAndSpanReport extends EnvQualityControlReport {
    // 报告标题
    private String reportName = "仪器运行状况检查记录表";
    // 报告类型
    private String reportType = ReportTypeEnum.ZERO_SPAN.getCode();
    // 报告类型中文
    private String reportDisplayType = ReportTypeEnum.ZERO_SPAN.getDisplayName();
    // 报表组件
    private String component = ReportTypeEnum.ZERO_SPAN.getComponent();
    // 报表内容，需要组装
    private Map<String, Object> reportData = new HashMap<>();
    // 报表内容，用于数据库存储
    private String reportContent = "";
    // 校准日期
    private Date reportDate;
    // 设备名称及编号
    private String instrumentNameAndNo = "";
    // 标气来源及编号
    private String gasSourceAndNo = "";
    // 标气浓度
    private String gasConcentration = "";
    // 使用满量程
    private String fullSpan = "";
    // 零点-开始时间
    private String zeroStartTime = "";
    // 零点-结束时间
    private String zeroEndTime = "";
    // 零点-标准浓度
    private String zeroStandardConcentration = "";
    // 零点-显示值
    private String zeroDisplayResponse = "";
    // 零点-标定值
    private String zeroCalibrationResponse = "";
    // 零点-漂移
    private String zeroDriftResult = "";
    // 满量程的80%-开始时间
    private String span80StartTime = "";
    // 满量程的80%-结束时间
    private String span80EndTime = "";
    // 满量程的80%-标准浓度
    private String span80StandardConcentration = "";
    // 满量程的80%-显示值
    private String span80DisplayResponse = "";
    // 满量程的80%-标定值
    private String span80CalibrationResponse = "";
    // 满量程的80%-漂移
    private String span80DriftResult = "";
    // 关键参数
    private List< Map<String, Object> > keyParameters = new ArrayList<>();
    // 备注
    private String reportNote = "";
    // 填表人
    private String filer = "";
    // 复核人
    private String reviewer = "";
    // 校准结果
    private String zeroCalibrationResult = "";
    // 校准结果
    private String spanCalibrationResult = "";
    /** 零跨合并展示：来自各记录 execution_log 根级 {@code qcPhaseTimelines}。 */
    private List<Map<String, Object>> qcPhaseTimelinesForReport = new ArrayList<>();
    /**
     * 是否展示「标定值-响应浓度」：仅当对应质控记录实际执行并完成自动校准相位（timeline 中带 endTimeMillis 的 calibration）时。
     */
    private boolean zeroCalibrationValueApplicable;
    private boolean spanCalibrationValueApplicable;

}

/**
 * MultiCheckReport
 * 用于生成 xxx仪器多点校准记录表
 */
@Data
class MultiCheckReport extends EnvQualityControlReport {

    // 报告标题
    private String reportName = "仪器多点校准记录表";
    // 报告类型
    private String reportType = ReportTypeEnum.MULTI.getCode();
    // 报告类型中文
    private String reportDisplayType = ReportTypeEnum.MULTI.getDisplayName();
    // 报表组件
    private String component = ReportTypeEnum.MULTI.getComponent();
    // 报表内容，需要组装
    private Map<String, Object> reportData = new HashMap<>();
    // 报表内容，用于数据库存储
    private String reportContent = "";
    // 校准日期
    private Date reportDate;
    // 设备名称及编号
    private String instrumentNameAndNo = "";
    // 标气来源及编号
    private String gasSourceAndNo = "";
    // 标气浓度
    private String gasConcentration = "";
    // 通入仪器的标气浓度
    private List<Float> gasConcentrationsInput = new ArrayList<>(6);
    // 仪器响应值
    private List<Float> instrumentResponses = new ArrayList<>(6);
    // 校准曲线
    private String formula = "";
    // 斜率
    private String a = "";
    // 截距
    private String b = "";
    // 相关系数
    private String r = "";
    // 校准结果
    private String calibrationResult = "";
    // 填表人
    private String filer = "";
    // 复核人
    private String reviewer = "";
}

/**
 * PrecisionReport
 * 用于生成 xxx仪器精密度审核记录表
 */
@Data
class PrecisionReport extends EnvQualityControlReport {
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
    private Date reportDate;
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

/**
 * AccuracyReport
 * 用于生成 xxx仪器准确度审核记录表
 */
@Data
class AccuracyReport extends EnvQualityControlReport {
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
    private Date reportDate;
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

/**
 * ConversionReport
 * 用于生成 xxx分析仪转换效率测试记录表
 * 目前就是 氮氧化物分析仪转换效率测试记录表
 */
@Data
class ConversionReport extends EnvQualityControlReport {
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
    private Date reportDate;
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

/**
 * TransferAndTraceReport
 * 用于生成 xxx校准设备量值传递与溯源记录表
 * 目前就是 臭氧校准设备量值传递记录表
 */
@Data
class TransferAndTraceReport extends EnvQualityControlReport {
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
    private Date reportDate;
    // 设备名称和编号
    private String instrumentNameAndNo;



}

/**
 * AuditSpanReport
 * 用于生成 人工核查 记录表
 */
@Data
class AuditSpanReport extends EnvQualityControlReport {
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
    private Date reportDate;
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
