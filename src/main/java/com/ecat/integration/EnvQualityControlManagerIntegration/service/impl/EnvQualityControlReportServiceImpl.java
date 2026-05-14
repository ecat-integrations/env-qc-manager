package com.ecat.integration.EnvQualityControlManagerIntegration.service.impl;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.EnvQualityControlReportMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlReportService;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.JsonUtils;
import com.ruoyi.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * EnvQualityControlReportServiceImpl
 * 
 * @author caohongbo
 * @version 1.0
 */

@Service
public class EnvQualityControlReportServiceImpl implements IEnvQualityControlReportService {

    @Autowired
    private EnvQualityControlReportMapper envQualityControlReportMapper;

    @Override
    public List<EnvQualityControlReport> findPage(EnvQualityControlReport envQualityControlReport) {

        List<EnvQualityControlReport> reports = envQualityControlReportMapper.findPage(envQualityControlReport);
        for (EnvQualityControlReport report : reports) {
            String reportContent = report.getReportContent();
            // 待查看的-报表内容-反序列化
            Map<String, Object> reportData = JsonUtils.parseMap(reportContent, String.class, Object.class);
            // System.out.println(reportContent);
            report.setReportData(reportData);
            report.setComponent((String) reportData.getOrDefault("component", ""));
            report.setReportDisplayType((String) reportData.getOrDefault("report_display_type", ""));
            // 将数据库存储的1,2,3,4转为用于前端显示见名知意的SO2,NO2,O3,CO
            report.setGasType((String) reportData.getOrDefault("gas_Type", report.getGasType()));
        }
        return reports;
    }
    @Override
    public EnvQualityControlReport getDetailById(Long id) {
        return envQualityControlReportMapper.selectDetailById(id);
    }


    /**
     * 新增质控报告
     *
     * @param envQualityControlReport 质控报告
     * @return 结果
     */
    @Override
    public int save(EnvQualityControlReport envQualityControlReport)
    {
        envQualityControlReport.setCreateTime(DateUtils.getNowDate());
        return envQualityControlReportMapper.save(envQualityControlReport);
    }

    /**
     * 修改质控报告
     *
     * @param envQualityControlReport 质控报告
     * @return 结果
     */
    @Override
    public int updateById(EnvQualityControlReport envQualityControlReport)
    {
        envQualityControlReport.setUpdateTime(DateUtils.getNowDate());
        return envQualityControlReportMapper.updateById(envQualityControlReport);
    }

    @Override
    @Transactional
    public boolean batchDiscard(List<Long> ids, Boolean isDiscarded) {
        int count = envQualityControlReportMapper.batchUpdateDiscarded(ids, isDiscarded);
        return count > 0;
    }

    @Override
    public List<Map<String, Object>> countByReportType() {
        return envQualityControlReportMapper.countByReportType();
    }

    @Override
    public List<EnvQualityControlReport> getByDateRange(Date startDate, Date endDate) {
        return envQualityControlReportMapper.selectByDateRange(startDate, endDate);
    }
     @Override
    public List<EnvQualityControlReport> getByDateRangeByCreateTime(Date startDate, Date endDate) {
        return envQualityControlReportMapper.selectByDateRangeByCreateTime(startDate, endDate);
    }
}
