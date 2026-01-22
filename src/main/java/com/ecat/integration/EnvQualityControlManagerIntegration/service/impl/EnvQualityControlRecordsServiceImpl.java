package com.ecat.integration.EnvQualityControlManagerIntegration.service.impl;

import java.util.*;

import com.ecat.core.EcatCore;
import com.ecat.integration.EnvDeviceCalibrationIntegration.ExecutorBase;
import com.ecat.integration.EnvDeviceCalibrationIntegration.PhaseInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.EnvQualityControlManagerIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ExecutionStatusEnum;
import com.ruoyi.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.EnvQualityControlRecordsMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;

import static com.ruoyi.common.utils.SecurityUtils.getUsername;

/**
 * 质控记录Service业务层处理
 * 
 * @author caohongbo
 * @date 2025-05-26
 */
@Service
public class EnvQualityControlRecordsServiceImpl implements IEnvQualityControlRecordsService 
{
    @Autowired
    private EnvQualityControlRecordsMapper envQualityControlRecordsMapper;
    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    @Autowired
    private EcatCore core;

    /**
     * 查询质控记录
     * 
     * @param id 质控记录主键
     * @return 质控记录
     */
    @Override
    public EnvQualityControlRecords selectEnvQualityControlRecordsById(Long id)
    {
        return envQualityControlRecordsMapper.selectEnvQualityControlRecordsById(id);
    }

    /**
     * 查询质控记录列表
     * 
     * @param envQualityControlRecords 质控记录
     * @return 质控记录
     */
    @Override
    public List<EnvQualityControlRecords> selectEnvQualityControlRecordsList(EnvQualityControlRecords envQualityControlRecords)
    {
        List<EnvQualityControlRecords> result =  envQualityControlRecordsMapper.selectEnvQualityControlRecordsList(envQualityControlRecords);
        try {
            EnvQualityControlManagerIntegration integration = (EnvQualityControlManagerIntegration) core.getIntegrationRegistry().getIntegration("integration-env-quality-control-manager");
            Map<Long, ExecutorBase> executorMap = integration.executorMap;
            // 新增阶段列表信息
            for (EnvQualityControlRecords record : result) {
                if(executorMap.containsKey(record.getId())){
                    ExecutorBase executor = executorMap.get(record.getId());
                    List<PhaseInfo> phaseInfos = executor.getExecutorPhases();
                    PhaseInfo currentPhase = executor.getCurrentPhase();
                    String currentPhaseId = currentPhase.getId();
                    List<Map<String,Object>> phaseList = new ArrayList<>();
                    for (PhaseInfo phaseInfo : phaseInfos) {
                        Map<String,Object> phaseMap = new HashMap<>();
                        phaseMap.put("phaseId", phaseInfo.getId());
                        phaseMap.put("phaseName", phaseInfo.getDisplayName());
                        phaseMap.put("phaseTime", phaseInfo.getEstimatedSeconds());
                        if(phaseInfo.getId().equals(currentPhaseId)){
                            phaseMap.put("currentPhase", true);
                        }
                        phaseList.add(phaseMap);
                    }
                    record.setPhaseList(phaseList);
                }
            }
        }catch (Exception e){
            log.error("查询质控阶段信息异常", e);
        }
        return result;
    }

    /**
     * 查询质控记录列表
     * @param  beginStartTime Date
     * @param  endStartTime Date
     * @return 质控记录
     */
    @Override
    public List<EnvQualityControlRecords> selectEnvQualityControlRecordsByTypeTime(Date beginStartTime, Date endStartTime)
    {
        return envQualityControlRecordsMapper.selectEnvQualityControlRecordsByTypeTime(beginStartTime, endStartTime, null,null);
    }
    /**
     * 查询质控记录列表
     * @param  beginStartTime Date
     * @param  endStartTime Date
     * @param qualityControlType String
     * @return 质控记录
     */
    @Override
    public List<EnvQualityControlRecords> selectEnvQualityControlRecordsByTypeTime(Date beginStartTime, Date endStartTime, String qualityControlType)
    {
        return envQualityControlRecordsMapper.selectEnvQualityControlRecordsByTypeTime(beginStartTime, endStartTime, qualityControlType, null);
    }
    /**
     * 查询质控记录列表
     * @param  beginStartTime Date
     * @param  endStartTime Date
     * @param qualityControlType String
     * @param executionStatus Long
     * @return 质控记录
     */
    @Override
    public List<EnvQualityControlRecords> selectEnvQualityControlRecordsByTypeTime(Date beginStartTime, Date endStartTime, String qualityControlType, Long executionStatus)
    {
        return envQualityControlRecordsMapper.selectEnvQualityControlRecordsByTypeTime(beginStartTime, endStartTime, qualityControlType, executionStatus);
    }

    /**
     * 新增质控记录
     * 
     * @param envQualityControlRecords 质控记录
     * @return 结果
     */
    @Override
    public int insertEnvQualityControlRecords(EnvQualityControlRecords envQualityControlRecords)
    {
        envQualityControlRecords.setCreateTime(DateUtils.getNowDate());
        return envQualityControlRecordsMapper.insertEnvQualityControlRecords(envQualityControlRecords);
    }

    /**
     * 修改质控记录
     * 
     * @param envQualityControlRecords 质控记录
     * @return 结果
     */
    @Override
    public int updateEnvQualityControlRecords(EnvQualityControlRecords envQualityControlRecords)
    {
        envQualityControlRecords.setUpdateTime(DateUtils.getNowDate());
        return envQualityControlRecordsMapper.updateEnvQualityControlRecords(envQualityControlRecords);
    }
     /**
     * 中止质控记录
     *
     * @param id 质控记录主键
     * @return 结果
     */
    @Override
    public Map<String, Object> stopEnvQualityControlRecords(Long id)
    {
        Map<String, Object> result = new HashMap<>();
        EnvQualityControlRecords record = envQualityControlRecordsMapper.selectEnvQualityControlRecordsById(id);
        if(record == null){
            result.put("code", 400);
            result.put("msg", "质控记录不存在");
            return result;
        }
        record.setExecutionStatus(ExecutionStatusEnum.STOPING.getCode());
        EnvQualityControlManagerIntegration integration = (EnvQualityControlManagerIntegration) core.getIntegrationRegistry().getIntegration("integration-env-quality-control-manager");
        Map<Long, ExecutorBase> executorMap = integration.executorMap;
        if(executorMap.containsKey(id)){
            ExecutorBase executor = executorMap.get(id);
            executor.stop();
        }
        record.setUpdateTime(new Date());
        record.setUpdatedBy(getUsername());
        envQualityControlRecordsMapper.updateEnvQualityControlRecords(record);
        result.put("code", 200);
        result.put("msg", "质控记录已中止");
        return result;
    }

    /**
     * 批量删除质控记录
     * 
     * @param ids 需要删除的质控记录主键
     * @return 结果
     */
    @Override
    public int deleteEnvQualityControlRecordsByIds(Long[] ids)
    {
        return envQualityControlRecordsMapper.deleteEnvQualityControlRecordsByIds(ids);
    }

    /**
     * 删除质控记录信息
     * 
     * @param id 质控记录主键
     * @return 结果
     */
    @Override
    public int deleteEnvQualityControlRecordsById(Long id)
    {
        return envQualityControlRecordsMapper.deleteEnvQualityControlRecordsById(id);
    }

    @Override
    public int updateRecordsExecutionStatus(int newExecutionStatus, String resultEvaluation, Date beginStartTime, Date endStartTime) {
        return envQualityControlRecordsMapper.updateRecordsExecutionStatus(newExecutionStatus, resultEvaluation, beginStartTime, endStartTime);
    }
}
