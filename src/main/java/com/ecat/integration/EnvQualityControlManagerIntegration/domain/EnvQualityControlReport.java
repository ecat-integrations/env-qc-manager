package com.ecat.integration.EnvQualityControlManagerIntegration.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;

/**
 * EnvQualityControlReport
 * 质控报告对象
 * 
 * @author caohongbo
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnvQualityControlReport extends BaseEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    // 自增主键ID
    private Long id;
    // 报表名称
    private String reportName;
    // 报表类型
    private String reportType;
    // 报表类型
    private String reportDisplayType;
    // 报表生成日期
    @JsonFormat(pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date reportDate;
    // 仪器名称
    private String instrumentName;
    // 仪器编号
    private String instrumentNo = "";
    // 标气类型
    private String gasType;
    // 标气来源
    private String gasSource;
    // 标气编号
    private String gasNo = "";
    // 填表人姓名
    private String filer;
    // 审核人姓名
    private String reviewer;
    // 运维公司
    private String maintenanceCompany;
    // 报表详细内容（text）
    private String reportContent;
    // 报表详细内容（json） 非数据库表字段， 仅接口返回前端
    private Map<String, Object> reportData;
    // 报表组件，前端点击查看时，知道展示哪个组件
    private String component;
    // 报表备注信息
    public String reportNote;
    // 是否废弃（默认false）
    private Boolean isDiscarded;
    // 是否合格
    private Boolean isPass;
    // 创建人ID或姓名
    private String createdBy;
    // 更新人ID或姓名
    private String updatedBy;
    // 记录创建时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    // 记录更新时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    // 查询起始日期
    @JsonFormat(pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date StartDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
}
