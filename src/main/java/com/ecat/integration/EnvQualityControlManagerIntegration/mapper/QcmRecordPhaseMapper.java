package com.ecat.integration.EnvQualityControlManagerIntegration.mapper;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPhase;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 质控执行阶段时间线子表 Mapper（qcm_record_phase，§4.0 强类型结构化）。
 * 完成时 insertBatch 一次性写入；读走 selectByRecordId（ORDER BY seq）零关联。
 *
 * @author coffee
 */
public interface QcmRecordPhaseMapper {

    /**
     * 批量写入阶段时间线（foreach 多 values；执行完成时一次调用）。
     */
    int insertBatch(@Param("records") List<QcmRecordPhase> records);

    /**
     * 按 record 查询阶段时间线，seq 升序。
     */
    List<QcmRecordPhase> selectByRecordId(Long recordId);

    /**
     * 按 record 删除子表行（应用层随记录删除同步清理）。
     */
    int deleteByRecordId(Long recordId);
}
