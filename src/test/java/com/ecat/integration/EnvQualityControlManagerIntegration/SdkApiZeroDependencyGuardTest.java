package com.ecat.integration.EnvQualityControlManagerIntegration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * api 包零依赖守卫（AC-B7）：对外 SDK 契约包只允许 java.* 与 lombok.*（编译期注解）依赖，
 * 任何 qcm 内部/Spring/ruoyi/composer import 都会让外部类加载器可见性破裂。
 */
class SdkApiZeroDependencyGuardTest {

    private static final Pattern ALLOWED_IMPORT =
            Pattern.compile("^import\\s+(static\\s+)?(java\\.|lombok\\.).*;\\s*$");

    @Test
    void apiPackageImportsAreJavaOrLombokOnly() throws IOException {
        Path apiDir = Paths.get("src", "main", "java", "com", "ecat", "integration",
                "EnvQualityControlManagerIntegration", "api");
        assertTrue(Files.isDirectory(apiDir), "api 源码目录应存在: " + apiDir);

        List<String> offenders = new ArrayList<>();
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(apiDir)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                files.add(p.getFileName().toString());
                List<String> lines;
                try {
                    lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("读取 api 源文件失败: " + p, e);
                }
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ") && !ALLOWED_IMPORT.matcher(trimmed).matches()) {
                        offenders.add(p.getFileName() + ": " + trimmed);
                    }
                }
            });
        }
        // 守卫自身防空扫：目录须含契约五文件
        assertTrue(files.size() >= 5, "api 包应至少含 5 个契约文件，实际: " + files);
        assertTrue(files.contains("QualityControlSdk.java"), "缺 SDK 接口: " + files);
        assertTrue(offenders.isEmpty(), "api 包出现非法依赖 import:\n" + String.join("\n", offenders));
    }
}
