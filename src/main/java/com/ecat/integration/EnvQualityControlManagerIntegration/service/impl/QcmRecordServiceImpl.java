package com.ecat.integration.EnvQualityControlManagerIntegration.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.StopOutcome;

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

    /**
     * 停止决策核心在编排器（设计 §8 上移，REST 与 SDK 共用）。本服务与编排器互相依赖：
     * 编排器构造注入本服务做回调落库，本服务又引用编排器——构造器边使环无法靠单侧字段注入解开
     * （编排器先被创建时，其构造参数解析先于自身实例化，本服务拿不到编排器早期引用，
     * 容器抛 BeanCurrentlyInCreationException）。@Lazy 注入代理，首次调用才解析目标，
     * 与两个 bean 的创建顺序无关，环稳定解开。
     */
    @Autowired
    @Lazy
    private QcmExecutionOrchestrator orchestrator;

    public QcmRecordServiceImpl() {
    }

    /** Spring 用法走字段注入 + 默认构造；本构造仅供单测直接装配 mock。 */
    public QcmRecordServiceImpl(QcmRecordMapper qcmRecordMapper, QcmExecutionOrchestrator orchestrator) {
        this.qcmRecordMapper = qcmRecordMapper;
        this.orchestrator = orchestrator;
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
     * 中止质控记录（FR-02-19/20/21）薄壳：批次定位、flow.stop、守卫 SQL 幂等、无执行器收敛等
     * 停止决策全部在 {@link QcmExecutionOrchestrator#stopExecution}（设计 §8 上移，REST 与 SDK 共用），
     * 本层只补 REST 侧来源（登录账号）并把统一结果翻成既有 Map 形态（code=200 受理 / 400 拒绝）。
     *
     * @param id 质控记录主键
     * @return 结果（code=200 已受理中止；code=400 记录不存在或批次已结算无需中止）
     */
    @Override
    public Map<String, Object> stopQcmRecord(Long id)
    {
        StopOutcome outcome = orchestrator.stopExecution(id, null, null, false, getUsername());
        Map<String, Object> result = new HashMap<>();
        result.put("code", outcome.getStatus() == StopOutcome.Status.INITIATED ? 200 : 400);
        result.put("msg", outcome.getMessage());
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
