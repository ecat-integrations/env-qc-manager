package com.ecat.integration.EnvQualityControlManagerIntegration.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import org.apache.ibatis.annotations.Param;

/**
 * 质控记录Mapper接口
 * 
 * @author caohongbo
 * @date 2025-05-26
 */
public interface EnvQualityControlRecordsMapper 
{
    /**
     * 查询质控记录
     * 
     * @param id 质控记录主键
     * @return 质控记录
     */
    public EnvQualityControlRecords selectEnvQualityControlRecordsById(Long id);

    /**
     * 查询质控记录列表
     * 
     * @param envQualityControlRecords 质控记录
     * @return 质控记录集合
     */
    public List<EnvQualityControlRecords> selectEnvQualityControlRecordsList(EnvQualityControlRecords envQualityControlRecords);

    /**
     * 查询质控记录列表，根据输入时间区间、质控类型、执行状态查询
     *
     * @param  beginStartTime Date
     * @param  endStartTime Date
     * @param qualityControlType String
     * @param executionStatus Long
     * @return 质控记录结果列表
     */
    public List<EnvQualityControlRecords> selectEnvQualityControlRecordsByTypeTime(@Param("beginStartTime") Date beginStartTime,
                                                                                   @Param("endStartTime") Date endStartTime,
                                                                                   @Param("qualityControlType") String qualityControlType,
                                                                                   @Param("executionStatus") Long executionStatus);

    /**
     * 新增质控记录
     * 
     * @param envQualityControlRecords 质控记录
     * @return 结果
     */
    public int insertEnvQualityControlRecords(EnvQualityControlRecords envQualityControlRecords);

    /**
     * 修改质控记录
     * 
     * @param envQualityControlRecords 质控记录
     * @return 结果
     */
    public int updateEnvQualityControlRecords(EnvQualityControlRecords envQualityControlRecords);

    /**
     * 删除质控记录
     * 
     * @param id 质控记录主键
     * @return 结果
     */
    public int deleteEnvQualityControlRecordsById(Long id);

    /**
     * 批量删除质控记录
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteEnvQualityControlRecordsByIds(Long[] ids);


    /**
     * 更新 executionStatus 和 resultEvaluation 字段，并根据条件筛选记录。
     *
     * @param newExecutionStatus 新的执行状态值
     * @param resultEvaluation   结果评估值
     * @param beginStartTime     开始时间（可选）
     * @param endStartTime       结束时间（可选）
     * @return 受影响的记录数量
     */
    public int updateRecordsExecutionStatus(
            @Param("newExecutionStatus") int newExecutionStatus,
            @Param("resultEvaluation") String resultEvaluation,
            @Param("beginStartTime") Date beginStartTime,
            @Param("endStartTime") Date endStartTime);

}
