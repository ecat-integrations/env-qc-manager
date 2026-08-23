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
 * ZeroAndSpanReport
 * 用于生成 xxx仪器运行状况检查记录表
 */
@Data
public class ZeroAndSpanReport extends QcmReport {
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
    private LocalDate reportDate;
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
