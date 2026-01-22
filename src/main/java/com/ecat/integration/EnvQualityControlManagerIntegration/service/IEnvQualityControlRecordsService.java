package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;

/**
 * 质控记录Service接口
 * 
 * @author caohongbo
 * @date 2025-05-26
 */
public interface IEnvQualityControlRecordsService 
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
     * 查询质控记录列表
     * @param  beginStartTime Date
     * @param  endStartTime Date
     * @return 质控记录集合
     */
    public List<EnvQualityControlRecords> selectEnvQualityControlRecordsByTypeTime(Date beginStartTime, Date endStartTime);
    /**
     * 查询质控记录列表
     * @param  beginStartTime Date
     * @param  endStartTime Date
     * @param qualityControlType String
     * @return 质控记录集合
     */
    public List<EnvQualityControlRecords> selectEnvQualityControlRecordsByTypeTime(Date beginStartTime, Date endStartTime, String qualityControlType);
    /**
     * 查询质控记录列表
     * @param  beginStartTime Date
     * @param  endStartTime Date
     * @param qualityControlType String
     * @param executionStatus Long
     * @return 质控记录集合
     */
    public List<EnvQualityControlRecords> selectEnvQualityControlRecordsByTypeTime(Date beginStartTime, Date endStartTime, String qualityControlType, Long executionStatus);

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
     * 中止质控记录
     *
     * @param id 质控记录id
     * @return 结果
     */
    public Map<String, Object> stopEnvQualityControlRecords(Long id);

    /**
     * 批量删除质控记录
     * 
     * @param ids 需要删除的质控记录主键集合
     * @return 结果
     */
    public int deleteEnvQualityControlRecordsByIds(Long[] ids);

    /**
     * 删除质控记录信息
     * 
     * @param id 质控记录主键
     * @return 结果
     */
    public int deleteEnvQualityControlRecordsById(Long id);

    public int updateRecordsExecutionStatus(int newExecutionStatus, String resultEvaluation, Date beginStartTime, Date endStartTime);
}
