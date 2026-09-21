package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.QcmReportExportVo;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ruoyi.common.annotation.Excel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表导出 VO 回归锁（bugs/bug-record-20260921-173000）：实体直传 ExcelUtil 时零 @Excel 字段
 * 产出全空工作表——本测试锁两件事：① ExcelUtil 视角列数（缺注解即红，正是原病灶形态）
 * ② 实体→VO 映射含编码翻译与时间预格式化。
 */
class QcmReportExportVoTest {

    /** 173000 病灶回归锁：导出 VO 必须每列带 @Excel，缺一列 ExcelUtil 就输出空单元格。 */
    @Test
    void everyColumnAnnotatedForExcelUtil() {
        List<String> unannotated = Arrays.stream(QcmReportExportVo.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .filter(f -> !f.isAnnotationPresent(Excel.class))
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(unannotated.isEmpty(), "导出列缺 @Excel 注解（ExcelUtil 会输出空单元格）: " + unannotated);
        long annotated = Arrays.stream(QcmReportExportVo.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Excel.class)).count();
        assertTrue(annotated >= 14, "导出列数不足: " + annotated);
    }

    @Test
    void mapsEntityToVoWithTranslationAndFormatting() {
        QcmReport r = new QcmReport();
        r.setReportName("9月报表");
        r.setReportType("daily");
        r.setReportDate(LocalDate.of(2026, 9, 18));
        r.setInstrumentName("分析仪A");
        r.setInstrumentNo("SN-001");
        r.setGasType("2");
        r.setGasSource("钢瓶");
        r.setGasNo("G-2026-01");
        r.setFiler("张三");
        r.setReviewer("李四");
        r.setMaintenanceCompany("某维护公司");
        r.setIsDiscarded(false);
        r.setCreatedBy("admin");
        r.setCreateTime(Instant.from(ZonedDateTime.of(2026, 9, 18, 14, 30, 0, 0, ZoneId.of("Asia/Shanghai"))));

        QcmReportExportVo vo = QcmReportExportVo.from(r);

        assertEquals("9月报表", vo.getReportName());
        assertEquals("2026-09-18", vo.getReportDate());
        assertEquals("分析仪A", vo.getInstrumentName());
        assertEquals("SN-001", vo.getInstrumentNo());
        assertEquals("钢瓶", vo.getGasSource());
        assertEquals("G-2026-01", vo.getGasNo());
        assertEquals("张三", vo.getFiler());
        assertEquals("李四", vo.getReviewer());
        assertEquals("某维护公司", vo.getMaintenanceCompany());
        assertEquals("否", vo.getIsDiscarded());
        assertEquals("admin", vo.getCreatedBy());
        assertEquals("2026-09-18 14:30:00", vo.getCreateTime());
        // 编码翻译：daily/gasType=2 的具体展示名取自对应枚举，断言「已非原编码」+ 与枚举一致
        com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum rte =
                com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum.findByCode("daily");
        assertEquals(rte != null ? rte.getDisplayName() : "daily", vo.getReportType());
        String expectedGas = com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum.getNameByCode("2");
        assertEquals(expectedGas != null ? expectedGas : "2", vo.getGasType());
        assertTrue(!vo.getReportType().equals("daily") || rte == null, "daily 应被翻译为展示名");
    }

    @Test
    void unknownCodesPassThroughAndNullsRenderEmpty() {
        QcmReport r = new QcmReport();
        r.setReportType("legacy_unknown");
        r.setGasType("99");
        QcmReportExportVo vo = QcmReportExportVo.from(r);
        assertEquals("legacy_unknown", vo.getReportType(), "未知编码透传原值（不猜默认）");
        String expectedGas = com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum.getNameByCode("99");
        assertEquals(expectedGas != null ? expectedGas : "99", vo.getGasType());
        assertEquals("", vo.getCreateTime(), "null 时间输出空串");
        assertEquals("", vo.getReportDate());
        assertEquals("", vo.getIsDiscarded(), "null Boolean 输出空串");
    }

    @Test
    void fromListKeepsOrderAndSize() {
        QcmReport a = new QcmReport();
        a.setReportName("A");
        QcmReport b = new QcmReport();
        b.setReportName("B");
        List<QcmReportExportVo> vos = QcmReportExportVo.fromList(Arrays.asList(a, b));
        assertEquals(2, vos.size());
        assertEquals("A", vos.get(0).getReportName());
        assertEquals("B", vos.get(1).getReportName());
    }
}
