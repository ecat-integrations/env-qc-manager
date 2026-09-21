package com.ecat.integration.EnvQualityControlManagerIntegration;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.QcmReportController;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.QcmReportExportVo;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmReportService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QcmReport LocalDate 直字段 form/query 绑定回归（bug-record-20260921-142143）：
 * reportDate/startDate/endDate 无 @DateTimeFormat 时 Spring 绑定层 ConversionFailed，
 * 活跃页面「报表日期」筛选与 report/export 阳性路径（守卫之前）均不可用。
 * 加注解后 yyyy-MM-dd 页面真实下发形态应绑定成功直达 service。
 */
class QcmReportDateBindingTest {

    private IQcmReportService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(IQcmReportService.class);
        // 匿名子类注入 protected service 字段并置空 Excel 写出钩子：本测试只锁绑定与守卫到达，不落真实文件
        QcmReportController controller = new QcmReportController() {
            {
                this.qcmReportService = QcmReportDateBindingTest.this.service;
            }

            @Override
            protected void writeExcel(HttpServletResponse response, List<QcmReportExportVo> vos) {
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listReportDateBindsToLocalDate() throws Exception {
        when(service.findPage(any())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/quality_control/report/list")
                        .param("pageNum", "1").param("pageSize", "10")
                        .param("reportDate", "2026-09-10"))
                .andExpect(status().isOk());

        ArgumentCaptor<QcmReport> captor = ArgumentCaptor.forClass(QcmReport.class);
        verify(service).findPage(captor.capture());
        assertEquals(LocalDate.of(2026, 9, 10), captor.getValue().getReportDate(),
                "reportDate=yyyy-MM-dd 应绑定成功并传入 findPage");
    }

    @Test
    void exportStartDateEndDateBindAndReachQuery() throws Exception {
        when(service.findPage(any())).thenReturn(Collections.emptyList());

        mockMvc.perform(post("/quality_control/report/export")
                        .param("startDate", "2026-09-01").param("endDate", "2026-09-19"))
                .andExpect(status().isOk());

        ArgumentCaptor<QcmReport> captor = ArgumentCaptor.forClass(QcmReport.class);
        verify(service, atLeastOnce()).findPage(captor.capture());
        assertEquals(LocalDate.of(2026, 9, 1), captor.getValue().getStartDate(),
                "startDate 应绑定成功（守卫双填检查之后到达查询）");
        assertEquals(LocalDate.of(2026, 9, 19), captor.getValue().getEndDate());
    }
}
