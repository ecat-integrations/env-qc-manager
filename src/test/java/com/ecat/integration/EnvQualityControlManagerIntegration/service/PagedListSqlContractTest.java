package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页 list SQL 全序契约锁：OFFSET 分页的排序键必须构成全序（时间键 + id 唯一
 * tiebreaker）——PG 对平键行返回序不保证稳定，平局跨页边界时页组成不确定（漏行/重行）。
 * 平局非理论值：records 受理批与 report 生成批的行共享同一 create/update 时间戳，
 * plan 集合工厂行微秒级相邻。断言方式沿用 insertBatch SQL 契约锁先例（读 XML 源断言）。
 *
 * @author coffee
 */
class PagedListSqlContractTest {

    private static String mapperXml(String name) throws Exception {
        return new String(Files.readAllBytes(
                Paths.get("src/main/resources/mapper/quality_control/" + name)),
                StandardCharsets.UTF_8);
    }

    /** 截取指定 statement 的 SQL 块（含 order by 尾部）。 */
    private static String statementBlock(String xml, String id) {
        int begin = xml.indexOf('"' + id + '"');
        assertTrue(begin >= 0, id + " 语句存在");
        int end = xml.indexOf("</select>", begin);
        assertTrue(end > begin, id + " 语句闭合");
        return xml.substring(begin, end);
    }

    /** 断言排序子句为「时间键 desc + id desc 唯一 tiebreaker」全序形态。 */
    private static void assertTotalOrder(String block, String timeKey, String id) {
        String normalized = block.toLowerCase().replaceAll("\\s+", " ");
        String expected = ("order by " + timeKey + " desc, " + id + " desc").toLowerCase();
        assertTrue(normalized.contains(expected),
                "排序须为全序（" + expected + "），否则 OFFSET 翻页在平局边界漏行/重行");
    }

    @Test
    void planSelectList_ordersWithIdTiebreaker() throws Exception {
        assertTotalOrder(statementBlock(mapperXml("QcmPlanMapper.xml"), "selectList"),
                "create_time", "id");
    }

    @Test
    void recordSelectList_ordersWithIdTiebreaker() throws Exception {
        assertTotalOrder(statementBlock(mapperXml("QcmRecordMapper.xml"), "selectList"),
                "create_time", "id");
    }

    @Test
    void reportFindPage_ordersWithIdTiebreaker() throws Exception {
        assertTotalOrder(statementBlock(mapperXml("QcmReportMapper.xml"), "findPage"),
                "update_time", "id");
    }
}
