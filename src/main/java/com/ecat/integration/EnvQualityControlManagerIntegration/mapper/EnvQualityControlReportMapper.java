package com.ecat.integration.EnvQualityControlManagerIntegration.mapper;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlReport;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 质控记录Mapper接口
 * 
 * @author caohongbo
 * @date 2025-05-26
 */


public interface EnvQualityControlReportMapper
{
    /**
     * 复杂条件分页查询
     */
    List<EnvQualityControlReport> findPage(EnvQualityControlReport envQualityControlReport);

    /**
     * 根据ID查询详情（包含JSONB解析）
     */
    EnvQualityControlReport selectDetailById(Long id);

    /**
     * 新增质控报告
     *
     * @param envQualityControlReport 质控报告
     * @return 结果
     */
    public int save(EnvQualityControlReport envQualityControlReport);

    /**
     * 修改质控报告
     *
     * @param envQualityControlReport 质控报告
     * @return 结果
     */
    public int updateById(EnvQualityControlReport envQualityControlReport);

    /**
     * 批量逻辑删除
     */
    int batchUpdateDiscarded(@Param("ids") List<Long> ids, @Param("isDiscarded") Boolean isDiscarded);

    /**
     * 统计各类报表数量
     */
    List<Map<String, Object>> countByReportType();

    /**
     * 按日期范围查询
     */
    List<EnvQualityControlReport> selectByDateRange(
            @Param("startDate") Date startDate,
            @Param("endDate") Date endDate);
     /**
     * 按日期范围查询
     */
    List<EnvQualityControlReport> selectByDateRangeByCreateTime(
            @Param("startDate") Date startDate,
            @Param("endDate") Date endDate);
}
