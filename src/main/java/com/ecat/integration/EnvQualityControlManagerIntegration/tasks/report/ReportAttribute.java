package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 报表对象工厂（G-STRUCT-3：各报表实体已拆分为本包独立文件）。
 * <p>按报表类型名返回对应报表实体；各实体继承基础质控报告类 {@link com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport} 并扩展字段。</p>
 *
 * @author caohongbo
 * @version 1.0
 */

public class ReportAttribute {
    // 根据质控类型返回对应的报表对象
    public static QcmReport getReport(String reportType) {

        switch (reportType) {
            // 零点或跨度，生成零跨报表
            case "zero_span":
                return new ZeroAndSpanReport();
            case "multi":
                return new MultiCheckReport();
            case "precision":
                return new PrecisionReport();
            case "accuracy":
                return new AccuracyReport();
            case "conversion":
                return new ConversionReport();
            case "calibration":
                return new TransferAndTraceReport();
            case "audit_span":
                return new AuditSpanReport();
        }
        throw new RuntimeException("Invalid qualityControlType: " + reportType);
    }
}


////////////////////////////////////////////////////////////////////////////////
