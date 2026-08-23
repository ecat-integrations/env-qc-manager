package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4.0 结果快照强类型层防漂移守卫：
 * 1) 域模型字段与 DDL 列一一对应（实体字段数 = DDL 列数，防新增列漏映射）；
 * 2) updateResultSnapshot 的 SET 列 = 设计要求的快照全集，且每个 #{prop} 均为 QcmRecord 真实字段
 *    （静态解析 XML，等价于 mock 验证 SQL 参数传递——MyBatis 单 POJO 参数按属性名取值，
 *    属性名不存在运行时才炸，此处在编译期前静态拦截）。
 */
class QcmRecordSnapshotMapperTest {

    private static final Path SQL = Paths.get("src/main/resources/sql/qcm_data.sql");
    private static final Path RECORD_XML =
        Paths.get("src/main/resources/mapper/quality_control/QcmRecordMapper.xml");

    /** QcmRecord 上非数据库的查询扩展字段（列表时间窗/params 通道），对账时剔除 */
    private static final Set<String> RECORD_NON_DB_FIELDS = new java.util.HashSet<>(
        Arrays.asList("QUERY_WINDOW_ZONE", "QUERY_WINDOW_FORMATTER", "beginEndTime", "endEndTime", "params"));

    /** DDL 解析：取 CREATE TABLE xxx ( ... ) 内的顶层列名（跳过表级约束与行内注释） */
    private List<String> ddlColumns(String table) throws Exception {
        String sql = new String(Files.readAllBytes(SQL), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS " + table + " \\((.*?)\\n\\);", Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "qcm_data.sql 应包含 CREATE TABLE " + table);
        return Arrays.stream(m.group(1).split("\n"))
            .map(String::trim)
            // 去掉行内注释（-- ...），剔除空行与表级约束行
            .map(l -> l.replaceAll("--.*$", "").trim())
            .filter(l -> !l.isEmpty() && !l.toUpperCase().startsWith("PRIMARY KEY"))
            .map(l -> l.split("\\s+")[0])
            .collect(Collectors.toList());
    }

    private Set<String> fieldNames(Class<?> clazz, Set<String> exclude) {
        return Arrays.stream(clazz.getDeclaredFields())
            .map(Field::getName)
            .filter(n -> !exclude.contains(n))
            .collect(Collectors.toSet());
    }

    @Test
    void qcmRecordFieldsMatchDdlColumns() throws Exception {
        List<String> columns = ddlColumns("qcm_record");
        assertEquals(39, columns.size(), "qcm_record DDL 列数（21 原有 + 18 新增，含 gas_concentration_unit），实际: " + columns);
        Set<String> dbFields = fieldNames(QcmRecord.class, RECORD_NON_DB_FIELDS);
        // 列名 snake_case → camelCase 后与字段名对账（Java 8：手写循环，不用 Java 9+ replaceAll(Function)）
        Set<String> colCamel = new java.util.HashSet<>();
        Matcher cm = Pattern.compile("_([a-z])").matcher("");
        for (String c : columns) {
            cm.reset(c);
            StringBuffer sb = new StringBuffer();
            while (cm.find()) {
                cm.appendReplacement(sb, cm.group(1).toUpperCase());
            }
            cm.appendTail(sb);
            colCamel.add(sb.toString());
        }
        assertEquals(colCamel, dbFields,
            "QcmRecord 字段与 qcm_record 列需一一对应（is_pass<->isPass 等），差异即防漂移信号");
    }

    @Test
    void snapshotSubEntityFieldsMatchDdlColumns() throws Exception {
        assertEquals(ddlColumns("qcm_record_phase").size(), 8);
        assertEquals(fieldNames(Class.forName(
            "com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPhase"),
            java.util.Collections.emptySet()).size(), 8);
        assertEquals(ddlColumns("qcm_record_key_param").size(), 7);
        assertEquals(fieldNames(Class.forName(
            "com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordKeyParam"),
            java.util.Collections.emptySet()).size(), 7);
        assertEquals(ddlColumns("qcm_record_point").size(), 5);
        assertEquals(fieldNames(Class.forName(
            "com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPoint"),
            java.util.Collections.emptySet()).size(), 5);
    }

    @Test
    void updateResultSnapshotSetsFullSnapshotColumns() throws Exception {
        String xml = new String(Files.readAllBytes(RECORD_XML), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(
            "<update id=\"updateResultSnapshot\".*?>(.*?)</update>", Pattern.DOTALL).matcher(xml);
        assertTrue(m.find(), "QcmRecordMapper.xml 应包含 updateResultSnapshot");
        String body = m.group(1);
        assertTrue(body.contains("where id = #{id}"), "updateResultSnapshot 必须 WHERE id 定位");
        // SET 子句单独对账（排除 where id = #{id} 干扰）
        body = body.substring(0, body.indexOf("where"));

        // 提取 `col = #{prop` 对，逐列断言属性名真实存在于 QcmRecord（参数传递防漂移）
        Map<String, String> colToProp = new LinkedHashMap<>();
        Matcher sm = Pattern.compile("([a-z_]+) = #\\{([a-zA-Z]+)").matcher(body);
        while (sm.find()) {
            colToProp.put(sm.group(1), sm.group(2));
        }
        Set<String> props = fieldNames(QcmRecord.class, RECORD_NON_DB_FIELDS);

        // §4.0 快照全集：判定标量（含激活三列）+ 快照层 + flow 排障关联 + 审计
        Set<String> expected = new java.util.HashSet<>(Arrays.asList(
            "standard_value", "monitoring_data", "calculated_value",
            "check_pass_limit", "check_calib_limit", "is_pass",
            "slope", "intercept", "correlation",
            "full_scale", "instrument_name", "instrument_no",
            "gas_source", "gas_no", "gas_concentration", "gas_concentration_unit",
            "sampling_start_time", "sampling_end_time",
            "flow_type", "flow_execution_ref", "update_time", "updated_by"));
        assertEquals(expected, colToProp.keySet(),
            "updateResultSnapshot 必须 SET 快照全集（一次性冻结），缺列/多列均为漂移");
        for (Map.Entry<String, String> e : colToProp.entrySet()) {
            assertTrue(props.contains(e.getValue()),
                "updateResultSnapshot 参数 " + e.getValue() + "（列 " + e.getKey() + "）不是 QcmRecord 字段，SQL 运行时取值将失败");
        }
    }
}
