package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.integration.EnvQualityControlManagerIntegration.util.PagedExportSupport;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出分批取数（AC-C7）：mock 分页返回验证「按页查询 + 聚合正确 + 短页即止」，
 * 以及聚合行数上限（超限明确报错、恰上限放行、不静默截断）。
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
        List<Integer> all = PagedExportSupport.loadAll(Integer.MAX_VALUE, (pageNum, pageSize) -> {
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
        List<Integer> all = PagedExportSupport.loadAll(Integer.MAX_VALUE, (p, s) -> {
            calls.incrementAndGet();
            return new ArrayList<>();
        });
        assertEquals(1, calls.get());
        assertTrue(all.isEmpty());
    }

    @Test
    void nullPage_treatedAsEnd() {
        List<Integer> all = PagedExportSupport.loadAll(Integer.MAX_VALUE, (p, s) -> p == 1 ? Arrays.asList(1, 2) : null);
        assertEquals(Arrays.asList(1, 2), all);
    }

    @Test
    void overMaxRows_throwsWithGuidance_andStopsPagingImmediately() {
        // 上限 507：第 1 页 500 未超继续，第 2 页聚合后 1000 > 507 当页即抛，不得拉第 3 页也不得静默截断。
        // loader 有界（3 满页后空页）：守卫缺失时聚合正常返回，本测试红在「没有抛错」而非死循环
        AtomicInteger calls = new AtomicInteger();
        int maxRows = PagedExportSupport.EXPORT_PAGE_SIZE + 7;
        ServiceException ex = assertThrows(ServiceException.class,
                () -> PagedExportSupport.loadAll(maxRows, (p, s) -> {
                    calls.incrementAndGet();
                    return p <= 3 ? pageOf((p - 1) * s + 1, s) : new ArrayList<>();
                }));
        assertTrue(ex.getMessage().contains(String.valueOf(maxRows)), "报错须带上限数值: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("分次导出"), "报错须引导分次导出: " + ex.getMessage());
        assertEquals(2, calls.get());
    }

    @Test
    void shortPageCrossingMaxRows_alsoRejected() {
        // 短页跨过上限同样拒绝：上限 507，第 2 页 20 行（短页）聚合后 520 > 507，先判上限再判短页终止
        ServiceException ex = assertThrows(ServiceException.class,
                () -> PagedExportSupport.loadAll(PagedExportSupport.EXPORT_PAGE_SIZE + 7, (p, s) ->
                        p == 1 ? pageOf(1, PagedExportSupport.EXPORT_PAGE_SIZE) : pageOf(501, 20)));
        assertTrue(ex.getMessage().contains("行上限"));
    }

    @Test
    void exactlyMaxRows_passes() {
        // 两满页恰好等于上限（1000）后空页收尾：恰上限放行，不得 off-by-one 拒绝
        AtomicInteger calls = new AtomicInteger();
        List<Integer> all = PagedExportSupport.loadAll(2 * PagedExportSupport.EXPORT_PAGE_SIZE, (p, s) -> {
            calls.incrementAndGet();
            return p <= 2 ? pageOf((p - 1) * PagedExportSupport.EXPORT_PAGE_SIZE + 1, PagedExportSupport.EXPORT_PAGE_SIZE)
                    : new ArrayList<>();
        });
        assertEquals(3, calls.get());
        assertEquals(2 * PagedExportSupport.EXPORT_PAGE_SIZE, all.size());
    }
}
