package com.ecat.integration.EnvQualityControlManagerIntegration.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 质控报告对象 qcm_report（FR-04-12：旧 env_quality_control_report 列与语义全量沿用，id 改 int8）。
 *
 * <p>report_date 为 date 列用 {@link LocalDate}；create_time/update_time 为 timestamptz 用 {@link Instant}。</p>
 *
 * @author coffee
 */
@Data
public class QcmReport {

    /** 报告 ID */
    private Long id;

    /** 报表名称 */
    private String reportName;

    /** 报表类型（如 zero_span/multi_check/precision_check/accuracy_check） */
    private String reportType;

    /** 报表生成日期（date 列） */
    private LocalDate reportDate;

    /** 仪器名称 */
    private String instrumentName;

    /** 仪器编号 */
    private String instrumentNo;

    /** 标气类型（如 SO2/NOx/O3/CO） */
    private String gasType;

    /** 标气来源 */
    private String gasSource;

    /** 标气编号 */
    private String gasNo;

    /** 填表人姓名 */
    private String filer;

    /** 审核人姓名 */
    private String reviewer;

    /** 运维公司 */
    private String maintenanceCompany;

    /** 报表详细内容（text，含校准/审核/测试所有数据） */
    private String reportContent;

    /** 报表备注信息 */
    private String reportNote;

    /** 是否废弃（默认 false） */
    private Boolean isDiscarded;

    /** 创建人 ID 或姓名 */
    private String createdBy;

    /** 更新人 ID 或姓名 */
    private String updatedBy;

    /** 记录创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant createTime;

    /** 记录更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant updateTime;

    // ===== 非数据库字段（仅接口返回前端）=====

    /** 报表详细内容（json 反序列化结果），仅接口返回前端 */
    private Map<String, Object> reportData;

    /** 报表组件，前端点击查看时知道展示哪个组件 */
    private String reportDisplayType;

    /** 报表组件名（reportData.component） */
    private String component;

    // ===== 非数据库查询扩展字段（列表筛选）=====

    /** 查询窗：起始日期（report_date/create_time 筛选用） */
    private LocalDate startDate;

    /** 查询窗：结束日期 */
    private LocalDate endDate;
}
