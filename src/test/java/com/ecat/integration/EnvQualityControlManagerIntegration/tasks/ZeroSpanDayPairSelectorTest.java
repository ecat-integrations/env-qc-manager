package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ZeroSpanDayPairSelector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.SPAN_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.ZERO_CHECK;
import static org.junit.jupiter.api.Assertions.*;

class ZeroSpanDayPairSelectorTest {

    @Test
    void bothPass_anyOrder_picksLatestEndSum() {
        EnvQualityControlRecords z = zero(day(10, 8), true);
        EnvQualityControlRecords s = span(day(10, 9), true);
        List<EnvQualityControlRecords> chosen = ZeroSpanDayPairSelector.select(list(z, s));
        assertEquals(2, chosen.size());
        assertSame(z, chosen.get(0));
        assertSame(s, chosen.get(1));
    }

    @Test
    void lastPassingZero_noSpanAfter_usesPriorSpan() {
        EnvQualityControlRecords sEarly = span(day(10, 6), false);
        EnvQualityControlRecords zPass = zero(day(10, 10), true);
        List<EnvQualityControlRecords> chosen = ZeroSpanDayPairSelector.select(list(sEarly, zPass));
        assertEquals(2, chosen.size());
        assertSame(zPass, chosen.get(0));
        assertSame(sEarly, chosen.get(1));
    }

    @Test
    void fallback_maxPassCount() {
        EnvQualityControlRecords zFail = zero(day(10, 8), false);
        EnvQualityControlRecords sPass = span(day(10, 9), true);
        List<EnvQualityControlRecords> chosen = ZeroSpanDayPairSelector.select(list(zFail, sPass));
        assertEquals(2, chosen.size());
    }

    @Test
    void spanOnly_returnsOne() {
        EnvQualityControlRecords s = span(day(10, 9), true);
        List<EnvQualityControlRecords> chosen = ZeroSpanDayPairSelector.select(Collections.singletonList(s));
        assertEquals(1, chosen.size());
        assertSame(s, chosen.get(0));
    }

    private static List<EnvQualityControlRecords> list(EnvQualityControlRecords... rs) {
        List<EnvQualityControlRecords> l = new ArrayList<>();
        for (EnvQualityControlRecords r : rs) {
            l.add(r);
        }
        return l;
    }

    private static EnvQualityControlRecords zero(Date start, boolean pass) {
        EnvQualityControlRecords r = new EnvQualityControlRecords();
        r.setQualityControlType(ZERO_CHECK.getCode());
        r.setParameter("1");
        r.setStartTime(start);
        r.setEndTime(new Date(start.getTime() + 60_000));
        r.setExecutionLog(buildLog(pass));
        return r;
    }

    private static EnvQualityControlRecords span(Date start, boolean pass) {
        EnvQualityControlRecords r = new EnvQualityControlRecords();
        r.setQualityControlType(SPAN_CHECK.getCode());
        r.setParameter("1");
        r.setStartTime(start);
        r.setEndTime(new Date(start.getTime() + 60_000));
        r.setExecutionLog(buildLog(pass));
        return r;
    }

    private static String buildLog(boolean pass) {
        return "{\"resultValue\":1,\"stdValue\":0,\"deviceValue\":0,\"checkPassLimit\":1,\"checkCalibLimit\":1,\"isPass\":"
                + pass + "}";
    }

    private static Date day(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.FEBRUARY, 10, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }
}
