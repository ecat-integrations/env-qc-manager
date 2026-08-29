package com.ecat.integration.EnvQualityControlManagerIntegration.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

import com.ecat.core.EcatCore;
import com.ecat.integration.EnvCalibrationComposerIntegration.AbstractCalibrationFlow;
import com.ecat.integration.EnvQualityControlManagerIntegration.EnvQualityControlManagerIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;

import static com.ruoyi.common.utils.SecurityUtils.getUsername;

/**
 * 质控记录Service业务层处理（qcm_record 换代：实体/Mapper 换新，方法名与语义沿用旧契约）
 *
 * @author caohongbo
 * @date 2025-05-26
 */
@Service
public class QcmRecordServiceImpl implements IQcmRecordService
{
    @Autowired
    private QcmRecordMapper qcmRecordMapper;
    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    @Autowired
    private EcatCore core;

    public QcmRecordServiceImpl() {
    }

    /** Spring 用法走字段注入 + 默认构造；本构造仅供单测直接装配 mock。 */
    public QcmRecordServiceImpl(QcmRecordMapper qcmRecordMapper, EcatCore core) {
        this.qcmRecordMapper = qcmRecordMapper;
        this.core = core;
    }

    /**
     * 查询质控记录
     *
     * @param id 质控记录主键
     * @return 质控记录
     */
    @Override
    public QcmRecord selectQcmRecordById(Long id)
    {
        return qcmRecordMapper.selectById(id);
    }

    /**
     * 查询质控记录列表
     *
     * @param qcmRecord 质控记录
     * @return 质控记录
     */
    @Override
    public List<QcmRecord> selectQcmRecordList(QcmRecord qcmRecord)
    {
        return qcmRecordMapper.selectList(qcmRecord);
    }

    /**
     * 查询质控记录列表
     * @param  beginStartTime Instant
     * @param  endStartTime Instant
     * @param qualityControlType String
     * @param executionStatus Integer
     * @return 质控记录
     */
    @Override
    public List<QcmRecord> selectQcmRecordByTypeTime(Instant beginStartTime, Instant endStartTime, String qualityControlType, Integer executionStatus)
    {
        return qcmRecordMapper.selectByTypeTime(beginStartTime, endStartTime, qualityControlType, executionStatus);
    }

    /**
     * 新增质控记录
     *
     * @param qcmRecord 质控记录
     * @return 结果
     */
    @Override
    public int insertQcmRecord(QcmRecord qcmRecord)
    {
        qcmRecord.setCreateTime(Instant.now());
        return qcmRecordMapper.insert(qcmRecord);
    }

    /**
     * 批量新增质控记录（G-BUG-10）：N 行同 batch_id 单语句写入=单事务原子，id 回填入参。
     */
    @Override
    @Transactional
    public int insertQcmRecordBatch(List<QcmRecord> records)
    {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("insertQcmRecordBatch requires at least one record");
        }
        Instant now = Instant.now();
        for (QcmRecord record : records) {
            record.setCreateTime(now);
        }
        int inserted = qcmRecordMapper.insertBatch(records);
        if (inserted != records.size()) {
            throw new IllegalStateException("批量写入质控记录不完整: 期望 " + records.size() + " 行，实际 " + inserted + " 行");
        }
        return inserted;
    }

    /**
     * 修改质控记录
     *
     * @param qcmRecord 质控记录
     * @return 结果
     */
    @Override
    public int updateQcmRecord(QcmRecord qcmRecord)
    {
        qcmRecord.setUpdateTime(Instant.now());
        return qcmRecordMapper.update(qcmRecord);
    }
     /**
     * 中止质控记录（FR-02-19/20/21，G-BUG-2/G-PERF-6）：
     * 存在性判断走轻量列查询（id/batch_id/execution_status，不整行加载 execution_log 大字段）；
     * 批次内含运行中 flow → flow.stop() 一次 + 批次全部行置 STOPING（终止终态由编排器回调落库）；
     * 无运行执行器（已结束/异常残留）→ 不再置 STOPING 挂死，直接 set-based 收敛到终止终态。
     *
     * @param id 质控记录主键
     * @return 结果
     */
    @Override
    public Map<String, Object> stopQcmRecord(Long id)
    {
        Map<String, Object> result = new HashMap<>();
        QcmRecord target = qcmRecordMapper.selectStopTargetById(id);
        if(target == null){
            result.put("code", 400);
            result.put("msg", "质控记录不存在");
            return result;
        }
        EnvQualityControlManagerIntegration integration = (EnvQualityControlManagerIntegration) core.getIntegrationRegistry().getIntegration("integration-env-qc-manager");
        Map<Long, AbstractCalibrationFlow> executorMap = integration.executorMap;
        List<Long> rowIds = target.getBatchId() != null
                ? qcmRecordMapper.selectBatchRowIds(target.getBatchId())
                : Collections.singletonList(id);
        log.info("stopQcmRecord:id={},batchId={},rows={},executorMap={}",
                id, target.getBatchId(), rowIds.size(), executorMap.size());
        Instant now = Instant.now();
        String updatedBy = getUsername();
        AbstractCalibrationFlow flow = null;
        for (Long rowId : rowIds) {
            AbstractCalibrationFlow candidate = executorMap.remove(rowId);
            if (candidate != null) {
                flow = candidate;
            }
        }
        if (flow != null) {
            flow.stop();
            log.info("stopQcmRecord:开启终止成功");
            for (Long rowId : rowIds) {
                qcmRecordMapper.markStopInProgressClearEndTime(
                        rowId, ExecutionStatusEnum.STOPPING.getCode().intValue(), now, updatedBy);
            }
            result.put("code", 200);
            result.put("msg", "质控记录已中止");
            return result;
        }
        // G-BUG-2 收敛修复：记录不在 executorMap（已结束/异常残留）→ 直接置终止终态，不留 STOPING 挂死
        log.warn("stopQcmRecord:id={} 无运行执行器，直接收敛为终止终态", id);
        String evaluation = "质控已中止（无运行执行器，直接收敛）";
        if (target.getBatchId() != null) {
            qcmRecordMapper.terminateByBatchId(target.getBatchId(),
                    ExecutionStatusEnum.FAILED.getCode().intValue(), evaluation, now, updatedBy);
        } else {
            qcmRecordMapper.terminateById(id,
                    ExecutionStatusEnum.FAILED.getCode().intValue(), evaluation, now, updatedBy);
        }
        result.put("code", 200);
        result.put("msg", evaluation);
        return result;
    }

    /**
     * 批量删除质控记录
     *
     * @param ids 需要删除的质控记录主键
     * @return 结果
     */
    @Override
    public int deleteQcmRecordByIds(Long[] ids)
    {
        return qcmRecordMapper.deleteByIds(ids);
    }

    /**
     * 删除质控记录信息
     *
     * @param id 质控记录主键
     * @return 结果
     */
    @Override
    public int deleteQcmRecordById(Long id)
    {
        return qcmRecordMapper.deleteById(id);
    }

    @Override
    public int updateRecordsExecutionStatus(int newExecutionStatus, String resultEvaluation, Instant beginStartTime, Instant endStartTime) {
        return qcmRecordMapper.updateRecordsExecutionStatus(newExecutionStatus, resultEvaluation, beginStartTime, endStartTime);
    }

    @Override
    public Instant resolveTerminalEndTime(Long recordId) {
        Instant now = Instant.now();
        if (recordId == null) {
            return now;
        }
        QcmRecord db = qcmRecordMapper.selectById(recordId);
        if (db != null && db.getUpdateTime() != null) {
            Instant u = db.getUpdateTime();
            if (!now.isAfter(u)) {
                return u.plusSeconds(1L);
            }
        }
        return now;
    }
}
