package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.QcmRecordController;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.QcmReportController;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.QcmRecordExportVo;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmReportService;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.PagedExportSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-C7 导出分批防回归：mock 分页返回 2 页，断言分批查询次数与聚合正确性。
 */
@ExtendWith(MockitoExtension.class)
class QcmExportBatchTest {

    @Mock
    private IQcmReportService reportService;

    @Mock
    private IQcmRecordService recordService;

    /** 绕开真实 Excel 写出（不依赖 HttpServletResponse 实现细节），只捕获聚合结果。 */
    private static class TestableReportController extends QcmReportController {
        List<QcmReport> captured;
        TestableReportController(IQcmReportService s) {
            this.qcmReportService = s;
        }
        @Override
        protected void writeExcel(HttpServletResponse response, List<QcmReport> list) {
            captured = list;
        }
    }

    private static class TestableRecordController extends QcmRecordController {
        List<QcmRecordExportVo> captured;
        TestableRecordController(IQcmRecordService s) {
            this.qcmRecordService = s;
        }
        @Override
        protected void writeExcel(HttpServletResponse response, List<QcmRecordExportVo> vos) {
            captured = vos;
        }
    }

    private static List<QcmReport> reportPage(int count) {
        List<QcmReport> l = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            QcmReport r = new QcmReport();
            r.setId((long) i);
            l.add(r);
        }
        return l;
    }

    private static List<QcmRecord> recordPage(int count) {
        List<QcmRecord> l = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            QcmRecord r = new QcmRecord();
            r.setId((long) i);
            l.add(r);
        }
        return l;
    }

    @Test
    void reportExport_queriesPerPageAndAggregates() {
        when(reportService.findPage(any(QcmReport.class)))
                .thenReturn(reportPage(PagedExportSupport.EXPORT_PAGE_SIZE))
                .thenReturn(reportPage(3));

        TestableReportController c = new TestableReportController(reportService);
        c.export(mock(HttpServletResponse.class), new QcmReport());

        verify(reportService, times(2)).findPage(any(QcmReport.class));
        assertEquals(PagedExportSupport.EXPORT_PAGE_SIZE + 3, c.captured.size());
    }

    @Test
    void recordExport_queriesPerPageAndAggregates() {
        when(recordService.selectQcmRecordList(any(QcmRecord.class)))
                .thenReturn(recordPage(PagedExportSupport.EXPORT_PAGE_SIZE))
                .thenReturn(recordPage(3));

        TestableRecordController c = new TestableRecordController(recordService);
        c.export(mock(HttpServletResponse.class), new QcmRecord());

        verify(recordService, times(2)).selectQcmRecordList(any(QcmRecord.class));
        assertEquals(PagedExportSupport.EXPORT_PAGE_SIZE + 3, c.captured.size());
    }
}
