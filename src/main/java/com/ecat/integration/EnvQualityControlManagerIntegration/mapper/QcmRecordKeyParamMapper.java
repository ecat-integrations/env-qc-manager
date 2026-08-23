package com.ecat.integration.EnvQualityControlManagerIntegration.mapper;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordKeyParam;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 质控执行关键参数子表 Mapper（qcm_record_key_param，§4.0 工况+溯源层）。
 *
 * @author coffee
 */
public interface QcmRecordKeyParamMapper {

    /**
     * 批量写入关键参数（foreach 多 values；执行完成时一次调用）。
     */
    int insertBatch(@Param("records") List<QcmRecordKeyParam> records);

    /**
     * 按 record 查询关键参数，seq 升序。
     */
    List<QcmRecordKeyParam> selectByRecordId(Long recordId);

    /**
     * 按 record 删除子表行（应用层随记录删除同步清理）。
     */
    int deleteByRecordId(Long recordId);
}
