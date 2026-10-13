package com.ecat.integration.EnvQualityControlManagerIntegration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V4.0.0 pre-release 重基线守卫：迁移目录只保留终态建库脚本，
 * 终态列直接随 CREATE TABLE 建立，旧通道清理不得进入新空库路径。
 */
class QcmMigrationRebaselineTest {

    private static final Path MIGRATION_DIR =
        Paths.get("src/main/resources/db/migration-qcm");
    private static final Path SQL = MIGRATION_DIR.resolve("V4.0.0__init.sql");

    @Test
    void migrationDirectoryKeepsOnlyTerminalBaseline() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            List<String> names = files.map(Path::getFileName)
                .map(Path::toString)
                .sorted()
                .collect(Collectors.toList());
            assertEquals(Collections.singletonList("V4.0.0__init.sql"), names);
        }
    }

    @Test
    void terminalColumnsAreCreatedDirectlyWithoutLegacyCleanup() throws IOException {
        String sql = readSql();
        assertFalse(sql.contains("DELETE FROM sys_job"));
        assertFalse(Pattern.compile("CREATE TABLE\\s+(IF NOT EXISTS\\s+)?env_quality_control_")
            .matcher(sql).find());
        assertFalse(Pattern.compile("^\\s*DROP\\s+TABLE", Pattern.CASE_INSENSITIVE)
            .matcher(sql).find());
        assertFalse(sql.contains("ALTER TABLE qcm_record"));
        assertFalse(sql.contains("ALTER TABLE qcm_plan"));

        String planDdl = ddlBlock(sql, "qcm_plan");
        assertTrue(planDdl.contains("calibration_policy varchar(30)"));
        assertTrue(planDdl.contains("same_day_priority varchar(10)"));

        String recordDdl = ddlBlock(sql, "qcm_record");
        assertTrue(recordDdl.contains("trigger_user varchar(100) NOT NULL"));
        assertTrue(recordDdl.contains("updated_by varchar(100) NOT NULL"));
        assertTrue(recordDdl.contains("check_pass_limit numeric"));
        assertTrue(recordDdl.contains("trigger_request_id varchar(36) NOT NULL"));
        assertTrue(recordDdl.contains("flow_type varchar(100)"));
        assertTrue(recordDdl.contains("flow_execution_ref varchar(100)"));
    }

    @Test
    void menuSeedUsesSequenceIdsWithoutReservedRangeOrMaxProbe() throws IOException {
        String sql = readSql();
        Matcher insertMatcher = Pattern
            .compile("INSERT\\s+INTO\\s+sys_menu\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
            .matcher(sql);
        assertTrue(insertMatcher.find(), "V4.0.0 应包含 sys_menu INSERT");
        do {
            assertFalse(Pattern.compile("(^|[,\\s])menu_id([,\\s]|$)")
                    .matcher(insertMatcher.group(1).trim()).find(),
                "sys_menu seed 不得显式写 menu_id");
        } while (insertMatcher.find());

        assertFalse(sql.contains("max(menu_id)"));
        assertFalse(sql.contains("2200"));
        assertFalse(sql.contains("2210"));
        assertTrue(sql.contains("SELECT menu_id INTO menu"));
        assertTrue(sql.contains("RETURNING menu_id INTO menu"));
        assertTrue(sql.contains("WHERE perms = p AND menu_type = 'F'"),
            "停用 C 菜单不能吸收启用 F 权限；按钮业务键必须按 perms+F 判定");
    }

    private String ddlBlock(String sql, String table) {
        Matcher matcher = Pattern.compile(
                "CREATE TABLE IF NOT EXISTS " + table + " \\((.*?)\\n\\);", Pattern.DOTALL)
            .matcher(sql);
        assertTrue(matcher.find(), "V4.0.0 应包含 CREATE TABLE " + table);
        return matcher.group(1).replaceAll("--.*$", "").replaceAll("\\s+", " ");
    }

    private String readSql() throws IOException {
        return new String(Files.readAllBytes(SQL), "UTF-8");
    }
}
