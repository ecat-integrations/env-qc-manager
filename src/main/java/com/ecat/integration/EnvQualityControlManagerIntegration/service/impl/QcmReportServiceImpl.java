package com.ecat.integration.EnvQualityControlManagerIntegration.service.impl;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmReportMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmReportService;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.JsonUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * QcmReportServiceImpl（qcm_report 换代：实体/Mapper 换新，方法名与语义沿用旧契约）
 *
 * @author caohongbo
 * @version 1.0
 */

@Service
public class QcmReportServiceImpl implements IQcmReportService {

    @Autowired
    private QcmReportMapper qcmReportMapper;

    /**
     * 列表页瘦身（AC-C6）：SQL 不取 report_content 大列，行级 JSON 反序列化/修补全部移除。
     * 「查看」弹窗改走 {@link #getDetailById(Long)} 详情接口取 reportData/component。
     * 报表类型展示列改由 DB report_type 数字码映射（content JSON 中 report_display_type 生成侧从未写入，恒为空串）。
     */
    @Override
    public List<QcmReport> findPage(QcmReport qcmReport) {
        List<QcmReport> reports = qcmReportMapper.findPage(qcmReport);
        for (QcmReport report : reports) {
            ReportTypeEnum type = ReportTypeEnum.findByCode(report.getReportType());
            report.setReportDisplayType(type != null ? type.getDisplayName() : "");
        }
        return reports;
    }

    /**
     * 详情接口：content JSON 在此一次性反序列化（列表不再承载该职责）。
     * qc_stamp 修补不下沉到读路径——生成侧 Gen*Report 的 constructReportContent 已统一写入。
     */
    @Override
    public QcmReport getDetailById(Long id) {
        QcmReport report = qcmReportMapper.selectDetailById(id);
        if (report != null && report.getReportContent() != null) {
            Map<String, Object> reportData = JsonUtils.parseMap(report.getReportContent(), String.class, Object.class);
            report.setReportData(reportData);
            report.setComponent((String) reportData.getOrDefault("component", ""));
            ReportTypeEnum type = ReportTypeEnum.findByCode(report.getReportType());
            report.setReportDisplayType(type != null ? type.getDisplayName() : "");
        }
        return report;
    }


    /**
     * 新增质控报告
     *
     * @param qcmReport 质控报告
     * @return 结果
     */
    @Override
    public int save(QcmReport qcmReport)
    {
        qcmReport.setCreateTime(Instant.now());
        return qcmReportMapper.save(qcmReport);
    }

    @Override
    public int saveBatch(List<QcmReport> reports)
    {
        if (reports == null || reports.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        for (QcmReport report : reports) {
            // insertBatch 全列写入：createTime/update_time 均须显式赋值（NOT NULL，G-BUG-17 同 G-BUG-14 模式）
            report.setCreateTime(now);
            report.setUpdateTime(now);
        }
        return qcmReportMapper.insertBatch(reports);
    }

    /**
     * 修改质控报告
     *
     * @param qcmReport 质控报告
     * @return 结果
     */
    @Override
    public int updateById(QcmReport qcmReport)
    {
        qcmReport.setUpdateTime(Instant.now());
        return qcmReportMapper.updateById(qcmReport);
    }

    @Override
    @Transactional
    public boolean batchDiscard(List<Long> ids, Boolean isDiscarded) {
        int count = qcmReportMapper.batchUpdateDiscarded(ids, isDiscarded);
        return count > 0;
    }

    @Override
    public List<Map<String, Object>> countByReportType() {
        return qcmReportMapper.countByReportType();
    }

    @Override
    public List<QcmReport> getByDateRange(LocalDate startDate, LocalDate endDate) {
        return qcmReportMapper.selectByDateRange(startDate, endDate);
    }
     @Override
    public List<QcmReport> getByDateRangeByCreateTime(Instant startDate, Instant endDate) {
        return qcmReportMapper.selectByDateRangeByCreateTime(startDate, endDate);
    }
}
