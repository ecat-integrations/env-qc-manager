package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 导出分批取数：用「每页一条 SQL」替代单条全量 SQL，避免一次载入全表（含大字段行）撑爆内存。
 * <p>调用方在 loader 内自行设置分页（如 PageHelper.startPage），本类只负责循环与聚合：
 * 按页拉取直到某页不足 pageSize 即止。聚合结果为全部行（导出需要整表），但任一时刻仅一页在途。</p>
 *
 * @author coffee
 */
public final class PagedExportSupport {

    /** 单页行数：500 行/页与 PageHelper 默认页大小生态一致，SQL 侧单页代价可控。 */
    public static final int EXPORT_PAGE_SIZE = 500;

    private PagedExportSupport() {
    }

    /**
     * @param loader (pageNum 从 1 起, pageSize) -> 该页数据
     * @param <T>    行类型
     * @return 聚合后的全部行
     */
    public static <T> List<T> loadAll(BiFunction<Integer, Integer, List<T>> loader) {
        List<T> all = new ArrayList<>();
        int pageNum = 1;
        while (true) {
            List<T> page = loader.apply(pageNum, EXPORT_PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                break;
            }
            all.addAll(page);
            if (page.size() < EXPORT_PAGE_SIZE) {
                break;
            }
            pageNum++;
        }
        return all;
    }
}
