package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.QcmRecordController;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.QcmReportController;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.QcmRecordExportVo;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.QcmReportExportVo;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmReportService;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.PagedExportSupport;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC-C7 导出分批防回归：mock 分页返回 2 页，断言分批查询次数与聚合正确性；
 * 以及导出守卫（时间窗必填防呆——mapper 时间条件双填才拼，缺起止即无界全表，导出必须先拦）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class QcmExportBatchTest {

    @Mock
    private IQcmReportService reportService;

    @Mock
    private IQcmRecordService recordService;

    /** 绕开真实 Excel 写出（不依赖 HttpServletResponse 实现细节），只捕获聚合结果。 */
    private static class TestableReportController extends QcmReportController {
        List<QcmReportExportVo> captured;
        TestableReportController(IQcmReportService s) {
            this.qcmReportService = s;
        }
        @Override
        protected void writeExcel(HttpServletResponse response, List<QcmReportExportVo> vos) {
            captured = vos;
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

        QcmReport query = new QcmReport();
        query.setStartDate(LocalDate.of(2026, 9, 1));
        query.setEndDate(LocalDate.of(2026, 9, 21));

        TestableReportController c = new TestableReportController(reportService);
        c.export(mock(HttpServletResponse.class), query);

        verify(reportService, times(2)).findPage(any(QcmReport.class));
        assertEquals(PagedExportSupport.EXPORT_PAGE_SIZE + 3, c.captured.size());
    }

    @Test
    void recordExport_queriesPerPageAndAggregates() {
        when(recordService.selectQcmRecordList(any(QcmRecord.class)))
                .thenReturn(recordPage(PagedExportSupport.EXPORT_PAGE_SIZE))
                .thenReturn(recordPage(3));

        QcmRecord query = new QcmRecord();
        query.setBeginStartTime(Instant.parse("2026-09-01T00:00:00Z"));
        query.setEndStartTime(Instant.parse("2026-09-21T15:59:59Z"));

        TestableRecordController c = new TestableRecordController(recordService);
        c.export(mock(HttpServletResponse.class), query);

        verify(recordService, times(2)).selectQcmRecordList(any(QcmRecord.class));
        assertEquals(PagedExportSupport.EXPORT_PAGE_SIZE + 3, c.captured.size());
    }

    private static void assertExportTimeWindowRequired(Executable exportCall) {
        ServiceException ex = assertThrows(ServiceException.class, exportCall);
        assertEquals("导出必须指定数据时间范围（起止时间）", ex.getMessage());
    }

    @Test
    void recordExport_withoutTimeWindow_rejectedBeforeAnyQuery() {
        TestableRecordController c = new TestableRecordController(recordService);
        assertExportTimeWindowRequired(() -> c.export(mock(HttpServletResponse.class), new QcmRecord()));
        verifyNoInteractions(recordService);
    }

    @Test
    void recordExport_halfTimeWindow_rejectedBeforeAnyQuery() {
        // mapper 双填才拼时间条件：只填起不填止 = 无时间条件，必须拒绝（经 params 通道验证解析后判定）
        QcmRecord query = new QcmRecord();
        Map<String, Object> params = new HashMap<>();
        params.put("beginStartTime", "2026-09-01 00:00:00");
        query.setParams(params);

        TestableRecordController c = new TestableRecordController(recordService);
        assertExportTimeWindowRequired(() -> c.export(mock(HttpServletResponse.class), query));
        verifyNoInteractions(recordService);
    }

    @Test
    void reportExport_withoutDateWindow_rejectedBeforeAnyQuery() {
        TestableReportController c = new TestableReportController(reportService);
        assertExportTimeWindowRequired(() -> c.export(mock(HttpServletResponse.class), new QcmReport()));
        verifyNoInteractions(reportService);
    }
}
