package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * IQcmReportService（qcm_report 换代后类型随新实体，方法名沿用旧契约最小改动）
 *
 * @author caohongbo
 * @version 1.0
 */

public interface IQcmReportService {

    /**
     * 复杂条件分页查询
     */
    List<QcmReport> findPage(QcmReport qcmReport);

    /**
     * 根据ID查询详情
     */
    QcmReport getDetailById(Long id);

    /**
     * 新增质控记录
     *
     * @param qcmReport 质控报告
     * @return 结果
     */
    int save(QcmReport qcmReport);

    /**
     * 批量新增报告（报告生成任务整批落库，失败条数由调用方汇总）
     *
     * @param reports 报告列表
     * @return 实际插入条数
     */
    int saveBatch(List<QcmReport> reports);

    /**
     * 修改质控记录
     *
     * @param qcmReport 质控报告
     * @return 结果
     */
    int updateById(QcmReport qcmReport);

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
    List<QcmReport> getByDateRange(LocalDate startDate, LocalDate endDate);

    /**
     * 按日期范围查询
     */
    List<QcmReport> getByDateRangeByCreateTime(Instant startDate, Instant endDate);
}
