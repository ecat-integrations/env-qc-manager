package com.ecat.integration.EnvQualityControlManagerIntegration.mapper;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 质控报告 Mapper 接口（qcm_report，FR-04-12：旧 report mapper 全语句平移）。
 *
 * @author coffee
 */
public interface QcmReportMapper {

    /**
     * 复杂条件分页查询（列表瘦身版：不含 report_content 大列，content 相关字段为 null）。
     */
    List<QcmReport> findPage(QcmReport report);

    /**
     * 根据ID查询详情（全列，含 report_content；service 层负责 content JSON 反序列化）。
     */
    QcmReport selectDetailById(Long id);

    /**
     * 新增报告（useGeneratedKeys 回填 id）。
     */
    int save(QcmReport report);

    /**
     * 批量新增报告（foreach 多 values；报告生成任务一次性落整批）。
     */
    int insertBatch(@Param("reports") List<QcmReport> reports);

    /**
     * 修改报告（非空字段动态 SET）。
     */
    int updateById(QcmReport report);

    /**
     * 批量逻辑删除（置 is_discarded）。
     */
    int batchUpdateDiscarded(@Param("ids") List<Long> ids, @Param("isDiscarded") Boolean isDiscarded);

    /**
     * 统计各类报表数量（排除已废弃）。
     */
    List<Map<String, Object>> countByReportType();

    /**
     * 按报表日期范围查询（排除已废弃）。
     */
    List<QcmReport> selectByDateRange(@Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);

    /**
     * 按创建时间范围查询（排除已废弃）。
     */
    List<QcmReport> selectByDateRangeByCreateTime(@Param("startDate") Instant startDate,
                                                  @Param("endDate") Instant endDate);
}
