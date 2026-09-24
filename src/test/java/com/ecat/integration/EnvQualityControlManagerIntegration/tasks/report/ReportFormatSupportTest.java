package com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report;

import com.ecat.core.Utils.DateTimeUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 报表浓度单位拼装口径：appendConcUnit 对成对串（值已带真实单位，如受理定格的 "400 ppm"）
 * 保留原单位，只有裸数字才按报告气体补默认单位——一本账后 cell 层直接喂成对串，
 * 单位以表列定格值为准，不得按气体类型重贴默认单位（D2 锁）。
 * 归日/展示的时间类辅助方法时区源 = ecat 平台时区（DateTimeUtils）。
 *
 * @author coffee
 */
class ReportFormatSupportTest {

    @AfterEach
    void restorePlatformZone() {
        DateTimeUtils.setZone(ZoneId.systemDefault());
    }

    /** 非 CO 报告气体收到已带 ppm 的成对串：保留 ppm，不得重贴 ppb（钢瓶档案浓度通常 ppm 量级）。 */
    @Test
    void appendConcUnit_pairedStringKeepsTrueUnit() {
        assertEquals("400 ppm", ReportFormatSupport.appendConcUnit("400 ppm", "2"));
        assertEquals("50 ppb", ReportFormatSupport.appendConcUnit("50 ppb", "1"));
    }

    /** 裸数字（无单位）才补默认单位：CO 报告气体 ppm，其余 ppb。 */
    @Test
    void appendConcUnit_bareNumberGetsDefaultUnit() {
        assertEquals("400 ppb", ReportFormatSupport.appendConcUnit("400", "2"));
        assertEquals("400 ppm", ReportFormatSupport.appendConcUnit("400", "4"));
    }

    /** 时区跟随锁：平台时区切到 UTC 后，归日与展示必须随 UTC 走（东八凌晨 01:00 = UTC 前一日 17:00）。 */
    @Test
    void reportDateAndTimeFollowPlatformZoneNotHardcoded() {
        DateTimeUtils.setZone(ZoneId.of("UTC"));
        Instant t = LocalDateTime.of(2026, 8, 20, 1, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        assertEquals(LocalDate.of(2026, 8, 19), ReportFormatSupport.reportDateOf(t));
        assertEquals("2026-08-19 17:00:00", ReportFormatSupport.fmtReportTime(t));
    }
}
