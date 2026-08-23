package com.ecat.integration.EnvQualityControlManagerIntegration.mapper;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 质控执行记录 Mapper 接口（qcm_record，FR-04-08）。
 * 语句按 qcm_record 表列定义（批次写入/查询为换代新增）。
 *
 * @author coffee
 */
public interface QcmRecordMapper {

    /**
     * 按主键查询记录。
     */
    QcmRecord selectById(Long id);

    /**
     * 中止入口的存在性/批次定位查询（G-PERF-6）：只取 id/batch_id/execution_status 三列，
     * 不整行加载 execution_log 等大字段。
     */
    QcmRecord selectStopTargetById(Long id);

    /**
     * 按批次标识查记录 id 列表（批次终止的逐行落库目标；升序与 selectByBatchId 一致）。
     */
    List<Long> selectBatchRowIds(String batchId);

    /**
     * 条件查询记录列表（taskType/qualityControlType 精确、executionStatus 精确、end_time 时间窗），按 create_time desc。
     */
    List<QcmRecord> selectList(QcmRecord record);

    /**
     * 按时间区间、质控类型、执行状态查询（executionStatus 缺省取 2=执行成功完成的记录）。
     */
    List<QcmRecord> selectByTypeTime(@Param("beginStartTime") Instant beginStartTime,
                                     @Param("endStartTime") Instant endStartTime,
                                     @Param("qualityControlType") String qualityControlType,
                                     @Param("executionStatus") Integer executionStatus);

    /**
     * 按批次标识查询（一次触发产生的 1..N 条同 batch_id 记录）。
     */
    List<QcmRecord> selectByBatchId(String batchId);

    /**
     * SDK 结果过滤查询（§4.1）：时间窗按 start_time（含边界）、qcType/instrument/triggerSource
     * （task_type 编码由调用方换算）/batchId/triggerRequestId 精确匹配，按 start_time 降序。
     * 调用方以 limit=上限+1 探测超限（防大窗全量拉取）。
     */
    List<QcmRecord> selectByFilter(@Param("begin") Instant begin,
                                   @Param("end") Instant end,
                                   @Param("qcType") String qcType,
                                   @Param("instrument") String instrument,
                                   @Param("triggerSource") String triggerSource,
                                   @Param("batchId") String batchId,
                                   @Param("triggerRequestId") String triggerRequestId,
                                   @Param("limit") int limit);

    /**
     * 新增记录（useGeneratedKeys 回填 id）。
     */
    int insert(QcmRecord record);

    /**
     * 批量新增：多仪器零点一次执行 N 行同 batch_id 写入（foreach 多 values）。
     */
    int insertBatch(@Param("records") List<QcmRecord> records);

    /**
     * 修改记录（非空字段动态 SET）。
     */
    int update(QcmRecord record);

    /**
     * 执行完成时一次性 UPDATE 结果快照（§4.0）：全部判定标量 + 快照层 + flow 关联列，
     * WHERE id 定位。与通用 update 分离：快照列只在完成时写一次，语义独立可守卫。
     */
    int updateResultSnapshot(QcmRecord record);

    /**
     * 用户发起中止：置执行状态并清空 end_time（真实结束时间在设备恢复完成后写入）。
     */
    int markStopInProgressClearEndTime(@Param("id") Long id,
                                       @Param("executionStatus") Integer executionStatus,
                                       @Param("updateTime") Instant updateTime,
                                       @Param("updatedBy") String updatedBy);

    /**
     * 单行直接收敛终止（G-BUG-2）：记录已无运行执行器（已结束/异常残留）时，
     * 中止请求不再置 STOPING 挂死，直接写终止终态 + end_time。
     */
    int terminateById(@Param("id") Long id,
                      @Param("executionStatus") Integer executionStatus,
                      @Param("resultEvaluation") String resultEvaluation,
                      @Param("endTime") Instant endTime,
                      @Param("updatedBy") String updatedBy);

    /**
     * 批次 set-based 终止（FR-02-20）：批次内全部行一次 UPDATE 落终止终态。
     */
    int terminateByBatchId(@Param("batchId") String batchId,
                           @Param("executionStatus") Integer executionStatus,
                           @Param("resultEvaluation") String resultEvaluation,
                           @Param("endTime") Instant endTime,
                           @Param("updatedBy") String updatedBy);

    /**
     * 更新 executionStatus in (0,1,4)（等待/运行中/手动中止中）的记录为新状态与结果评定，可选按 create_time 时间窗收窄。
     */
    int updateRecordsExecutionStatus(@Param("newExecutionStatus") int newExecutionStatus,
                                     @Param("resultEvaluation") String resultEvaluation,
                                     @Param("beginStartTime") Instant beginStartTime,
                                     @Param("endStartTime") Instant endStartTime);

    /**
     * 按主键删除记录。
     */
    int deleteById(Long id);

    /**
     * 批量删除记录。
     */
    int deleteByIds(Long[] ids);
}
