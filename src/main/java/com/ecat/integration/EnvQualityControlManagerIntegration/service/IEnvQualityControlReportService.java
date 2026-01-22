package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlReport;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * IEnvQualityControlReportService
 * 
 * @author caohongbo
 * @version 1.0
 */

public interface IEnvQualityControlReportService {

    /**
     * 复杂条件分页查询
     */
    List<EnvQualityControlReport> findPage(EnvQualityControlReport envQualityControlReport);

    /**
     * 根据ID查询详情
     */
    EnvQualityControlReport getDetailById(Long id);

    /**
     * 新增质控记录
     *
     * @param envQualityControlReport 质控记录
     * @return 结果
     */
    public int save(EnvQualityControlReport envQualityControlReport);

    /**
     * 修改质控记录
     *
     * @param envQualityControlReport 质控记录
     * @return 结果
     */
    public int updateById(EnvQualityControlReport envQualityControlReport);

    /**
     * 批量逻辑删除
     */
    boolean batchDiscard(List<Long> ids, Boolean isDiscarded);

    /**
     * 统计各类报表数量
     */
    List<Map<String, Object>> countByReportType();

    /**
     * 按日期范围查询
     */
    List<EnvQualityControlReport> getByDateRange(Date startDate, Date endDate);

    /**
     * 按日期范围查询
     */
    List<EnvQualityControlReport> getByDateRangeByCreateTime(Date startDate, Date endDate);
}
