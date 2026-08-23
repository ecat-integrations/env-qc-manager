package com.ecat.integration.EnvQualityControlManagerIntegration.mapper;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPoint;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 质控执行数据点子表 Mapper（qcm_record_point，§4.0 多点/精密度序列）。
 *
 * @author coffee
 */
public interface QcmRecordPointMapper {

    /**
     * 批量写入数据点（foreach 多 values；执行完成时一次调用）。
     */
    int insertBatch(@Param("records") List<QcmRecordPoint> records);

    /**
     * 按 record 查询数据点，seq 升序。
     */
    List<QcmRecordPoint> selectByRecordId(Long recordId);

    /**
     * 按 record 删除子表行（应用层随记录删除同步清理）。
     */
    int deleteByRecordId(Long recordId);
}
