package com.ecat.integration.EnvQualityControlManagerIntegration.domain;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 质控记录对象 env_quality_control_records
 * 
 * @author caohongbo
 * @date 2025-05-26
 */
public class EnvQualityControlRecords extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 记录ID */
    private Long id;

    /** 任务类型 */
    @Excel(name = "任务类型")
    private String taskType;

    /** 质控类型 */
    @Excel(name = "质控类型")
    private String qualityControlType;

    /** 质控参数 */
    @Excel(name = "质控参数")
    private String parameter;

    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "开始时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "结束时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /** 标准值 */
    @Excel(name = "标准值")
    private Double standardValue;

    /** 监测数据 */
    @Excel(name = "监测数据")
    private Double monitoringData;

    /** 计算值 */
    @Excel(name = "计算值")
    private Double calculatedValue;

    /** 执行状态 */
    @Excel(name = "执行状态")
    private Long executionStatus;

    /** 执行日志 */
    private String executionLog;

    /** 结果评价 */
    @Excel(name = "结果评价")
    private String resultEvaluation;

    /** 计划ID */
    private Long qualityControlPlanId;

    /** 创建人 */
    private String createdBy;

    /** 更新人 */
    private String updatedBy;

    /** 阶段列表-非数据库扩展字段 2025-12-31 */
    private List<Map<String,Object>> phaseList;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setTaskType(String taskType) 
    {
        this.taskType = taskType;
    }

    public String getTaskType() 
    {
        return taskType;
    }
    public void setQualityControlType(String qualityControlType) 
    {
        this.qualityControlType = qualityControlType;
    }

    public String getQualityControlType() 
    {
        return qualityControlType;
    }
    public void setParameter(String parameter) 
    {
        this.parameter = parameter;
    }

    public String getParameter() 
    {
        return parameter;
    }
    public void setStartTime(Date startTime) 
    {
        this.startTime = startTime;
    }

    public Date getStartTime() 
    {
        return startTime;
    }
    public void setEndTime(Date endTime) 
    {
        this.endTime = endTime;
    }

    public Date getEndTime() 
    {
        return endTime;
    }
    public void setStandardValue(Double standardValue) 
    {
        this.standardValue = standardValue;
    }

    public Double getStandardValue() 
    {
        return standardValue;
    }
    public void setMonitoringData(Double monitoringData) 
    {
        this.monitoringData = monitoringData;
    }

    public Double getMonitoringData() 
    {
        return monitoringData;
    }
    public void setCalculatedValue(Double calculatedValue) 
    {
        this.calculatedValue = calculatedValue;
    }

    public Double getCalculatedValue() 
    {
        return calculatedValue;
    }
    public void setExecutionStatus(Long executionStatus) 
    {
        this.executionStatus = executionStatus;
    }

    public Long getExecutionStatus() 
    {
        return executionStatus;
    }
    public void setExecutionLog(String executionLog) 
    {
        this.executionLog = executionLog;
    }

    public String getExecutionLog() 
    {
        return executionLog;
    }
    public void setResultEvaluation(String resultEvaluation) 
    {
        this.resultEvaluation = resultEvaluation;
    }

    public String getResultEvaluation() 
    {
        return resultEvaluation;
    }
    public void setQualityControlPlanId(Long qualityControlPlanId) 
    {
        this.qualityControlPlanId = qualityControlPlanId;
    }

    public Long getQualityControlPlanId() 
    {
        return qualityControlPlanId;
    }
    public void setCreatedBy(String createdBy) 
    {
        this.createdBy = createdBy;
    }

    public String getCreatedBy() 
    {
        return createdBy;
    }
    public void setUpdatedBy(String updatedBy) 
    {
        this.updatedBy = updatedBy;
    }

    public String getUpdatedBy() 
    {
        return updatedBy;
    }
    /**
     * 阶段列表-非数据库扩展字段 2025-12-31
     */
    public void setPhaseList(List<Map<String,Object>> phaseList)
    {
        this.phaseList = phaseList;
    }

    public List<Map<String,Object>> getPhaseList()
    {
        return phaseList;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("taskType", getTaskType())
            .append("qualityControlType", getQualityControlType())
            .append("parameter", getParameter())
            .append("startTime", getStartTime())
            .append("endTime", getEndTime())
            .append("standardValue", getStandardValue())
            .append("monitoringData", getMonitoringData())
            .append("calculatedValue", getCalculatedValue())
            .append("executionStatus", getExecutionStatus())
            .append("executionLog", getExecutionLog())
            .append("resultEvaluation", getResultEvaluation())
            .append("qualityControlPlanId", getQualityControlPlanId())
            .append("createdBy", getCreatedBy())
            .append("updatedBy", getUpdatedBy())
            .append("createTime", getCreateTime())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
