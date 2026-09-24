package com.ecat.integration.EnvQualityControlManagerIntegration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主源码硬编码时区守卫：src/main 禁出现 "Asia/Shanghai" 字面量。
 * 平台时区统一走 {@code DateTimeUtils.getZone()}（启动时从 core 配置加载、volatile 可变），
 * 查询窗解析/计划调度/报表归日/导出展示四域同源——任何域回退硬编码都会让 core 时区配置失效。
 * 本守卫覆盖归日大方法（ReportGenerator/GenReportTask 依赖 DB 难以直接单测）的行为面：
 * 它们回退硬编码必然写出该字面量。@JsonFormat(timezone = "GMT+8") 的 REST 展示注解不在本守卫范围。
 *
 * @author coffee
 */
class NoHardcodedZoneLiteralGuardTest {

    @Test
    void mainSourcesMustNotHardcodeAsiaShanghaiZone() throws IOException {
        Path root = Paths.get("src/main/java");
        assertTrue(Files.isDirectory(root), "应存在 src/main/java（测试须在模块根目录运行）");
        List<String> offenders = Files.walk(root)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    try {
                        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                                .contains("Asia/Shanghai");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .map(Path::toString)
                .collect(Collectors.toList());
        assertTrue(offenders.isEmpty(),
                "主源码禁硬编码 Asia/Shanghai：时区须取 DateTimeUtils.getZone() 与平台配置同源。违例文件: " + offenders);
    }
}
