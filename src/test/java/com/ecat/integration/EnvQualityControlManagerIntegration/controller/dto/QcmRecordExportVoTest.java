package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;

/**
 * 质控记录导出 VO 拷贝与 Instant 预格式化（Asia/Shanghai）单测。
 *
 * @author coffee
 */
public class QcmRecordExportVoTest {

    @Test
    public void from_instantFormattedAsShanghaiWallClock() {
        // 2026-08-14 00:00 UTC = 上海 08:00
        Instant start = Instant.parse("2026-08-14T00:00:00Z");
        QcmRecord r = new QcmRecord();
        r.setTaskType("0");
        r.setTriggerUser("system");
        r.setQualityControlType("1");
        r.setParameter("SO2");
        r.setStartTime(start);
        r.setEndTime(start.plusSeconds(3600));
        r.setStandardValue(new BigDecimal("0.5"));
        r.setMonitoringData(new BigDecimal("0.51"));
        r.setCalculatedValue(new BigDecimal("0.001"));
        r.setExecutionStatus(2);
        r.setResultEvaluation("合格");

        QcmRecordExportVo vo = QcmRecordExportVo.from(r);
        // 计划触发不带操作人（调度用户 system 是噪音），质控类型编码译为展示名（校准→检查）
        assertEquals("计划触发", vo.getTaskType());
        assertEquals("跨度检查", vo.getQualityControlType());
        // 质控参数已是符号时透传
        assertEquals("SO2", vo.getParameter());
        assertEquals("2026-08-14 08:00:00", vo.getStartTime());
        assertEquals("2026-08-14 09:00:00", vo.getEndTime());
        assertEquals(new BigDecimal("0.5"), vo.getStandardValue());
        assertEquals(Integer.valueOf(2), vo.getExecutionStatus());
        assertEquals("合格", vo.getResultEvaluation());
    }

    @Test
    public void from_triggerSourceTextFollowsPageDetailRules() {
        // 手动（1）/现场（2）= 展示名 + 操作人
        assertEquals("手动触发 admin", triggerSource("1", "admin"));
        assertEquals("现场任务 operatorA", triggerSource("2", "operatorA"));
        // 远程（3）= 展示名 + 来源前缀
        assertEquals("远程平台触发 来源：saiyunSystem", triggerSource("3", "saiyunSystem"));
        // 计划（0）即使带触发者也不显示（system 噪音）
        assertEquals("计划触发", triggerSource("0", "system"));
        // 触发者空：仅展示名
        assertEquals("远程平台触发", triggerSource("3", null));
        // 未知/遗留编码透传原值，不猜默认
        assertEquals("定时 legacy", triggerSource("定时", "legacy"));
        assertNull(triggerSource(null, "admin"));
    }

    @Test
    public void from_parameterCodeTranslatedToGasSymbol() {
        // 落库存 ParameterEnum 数字码，导出译为化学符号；未知值透传
        assertEquals("SO2", parameter("1"));
        assertEquals("CO", parameter("4"));
        assertEquals("PM2.5", parameter("6"));
        assertEquals("unknown", parameter("unknown"));
    }

    @Test
    public void from_qcTypeUnknownCodePassthroughRawValue() {
        // legacy 值（如 calibration_check）与未知编码原样透传
        assertEquals("calibration_check", qcType("calibration_check"));
        assertEquals("9", qcType("9"));
        assertEquals("零点检查", qcType("0"));
        assertEquals("准确度检查", qcType("4"));
    }

    @Test
    public void from_nullTimesExportedAsEmptyString() {
        QcmRecord r = new QcmRecord();
        QcmRecordExportVo vo = QcmRecordExportVo.from(r);
        assertEquals("", vo.getStartTime());
        assertEquals("", vo.getEndTime());
    }

    @Test
    public void formatter_roundTripWithQueryWindowParse() {
        Instant t = LocalDateTime.parse("2026-01-02 03:04:05",
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        QcmRecord r = new QcmRecord();
        r.setStartTime(t);
        assertEquals("2026-01-02 03:04:05", QcmRecordExportVo.from(r).getStartTime());
    }

    private static String triggerSource(String taskType, String triggerUser) {
        QcmRecord r = new QcmRecord();
        r.setTaskType(taskType);
        r.setTriggerUser(triggerUser);
        return QcmRecordExportVo.from(r).getTaskType();
    }

    private static String qcType(String code) {
        QcmRecord r = new QcmRecord();
        r.setQualityControlType(code);
        return QcmRecordExportVo.from(r).getQualityControlType();
    }

    private static String parameter(String code) {
        QcmRecord r = new QcmRecord();
        r.setParameter(code);
        return QcmRecordExportVo.from(r).getParameter();
    }
}
