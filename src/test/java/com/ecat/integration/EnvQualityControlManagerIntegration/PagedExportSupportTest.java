package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.integration.EnvQualityControlManagerIntegration.util.PagedExportSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出分批取数（AC-C7）：mock 分页返回验证「按页查询 + 聚合正确 + 短页即止」。
 *
 * @author coffee
 */
class PagedExportSupportTest {

    private static List<Integer> pageOf(int from, int count) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            l.add(from + i);
        }
        return l;
    }

    @Test
    void twoPages_aggregatedAndQueriedExactlyTwice() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> all = PagedExportSupport.loadAll((pageNum, pageSize) -> {
            calls.incrementAndGet();
            assertEquals(PagedExportSupport.EXPORT_PAGE_SIZE, pageSize);
            return pageNum == 1
                    ? pageOf(1, PagedExportSupport.EXPORT_PAGE_SIZE)
                    : pageOf(501, 7); // 第二页短页
        });
        assertEquals(2, calls.get());
        assertEquals(PagedExportSupport.EXPORT_PAGE_SIZE + 7, all.size());
        assertEquals(Integer.valueOf(1), all.get(0));
        assertEquals(Integer.valueOf(507), all.get(all.size() - 1));
    }

    @Test
    void emptyFirstPage_stopsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> all = PagedExportSupport.loadAll((p, s) -> {
            calls.incrementAndGet();
            return new ArrayList<>();
        });
        assertEquals(1, calls.get());
        assertTrue(all.isEmpty());
    }

    @Test
    void nullPage_treatedAsEnd() {
        List<Integer> all = PagedExportSupport.loadAll((p, s) -> p == 1 ? Arrays.asList(1, 2) : null);
        assertEquals(Arrays.asList(1, 2), all);
    }
}
