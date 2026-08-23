package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;

/**
 * 质控记录Service接口（qcm_record 换代后类型随新实体，方法名沿用旧契约最小改动）
 *
 * @author caohongbo
 * @date 2025-05-26
 */
public interface IQcmRecordService
{
    /**
     * 查询质控记录
     *
     * @param id 质控记录主键
     * @return 质控记录
     */
    QcmRecord selectQcmRecordById(Long id);

    /**
     * 查询质控记录列表
     *
     * @param qcmRecord 质控记录
     * @return 质控记录
     */
    List<QcmRecord> selectQcmRecordList(QcmRecord qcmRecord);

    /**
     * 查询质控记录列表
     * @param  beginStartTime Instant
     * @param  endStartTime Instant
     * @param qualityControlType String
     * @param executionStatus Integer
     * @return 质控记录
     */
    List<QcmRecord> selectQcmRecordByTypeTime(Instant beginStartTime, Instant endStartTime, String qualityControlType, Integer executionStatus);

    /**
     * 新增质控记录
     *
     * @param qcmRecord 质控记录
     * @return 结果
     */
    int insertQcmRecord(QcmRecord qcmRecord);

    /**
     * 批量新增质控记录：一次触发 N 台仪器 N 行同 batch_id，单语句单事务写入（G-BUG-10）；
     * id 由 useGeneratedKeys 回填到入参对象。
     */
    int insertQcmRecordBatch(List<QcmRecord> records);

    /**
     * 修改质控记录
     *
     * @param qcmRecord 质控记录
     * @return 结果
     */
    int updateQcmRecord(QcmRecord qcmRecord);

     /**
     * 中止质控记录
     *
     * @param id 质控记录id
     * @return 结果
     */
    Map<String, Object> stopQcmRecord(Long id);

    /**
     * 批量删除质控记录
     *
     * @param ids 需要删除的质控记录主键集合
     * @return 结果
     */
    int deleteQcmRecordByIds(Long[] ids);

    /**
     * 删除质控记录信息
     *
     * @param id 质控记录主键
     * @return 结果
     */
    int deleteQcmRecordById(Long id);

    int updateRecordsExecutionStatus(int newExecutionStatus, String resultEvaluation, Instant beginStartTime, Instant endStartTime);

    /**
     * 写入任务结束时间：保证严格晚于库中当前 {@code update_time}（含用户点击「中止质控」触发的更新），避免与开始时间或中止时刻重叠。
     */
    Instant resolveTerminalEndTime(Long recordId);
}
