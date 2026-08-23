package com.ecat.integration.EnvQualityControlManagerIntegration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * mapper XML 注入守卫：全模块 mapper 禁用 ${params.} 字符串拼接（审计 S2，验收红线 RED-2）。
 */
class MapperXmlInjectionGuardTest {

    @Test
    void allMapperXmlMustNotUseDollarParamInterpolation() throws Exception {
        List<Path> xmls = Files.walk(Paths.get("src/main/resources/mapper"))
            .filter(p -> p.toString().endsWith(".xml"))
            .collect(Collectors.toList());
        assertFalse(xmls.isEmpty(), "应至少存在一个 mapper XML");
        for (Path xml : xmls) {
            String content = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
            assertFalse(content.contains("${params."),
                xml + " 禁用 ${} 字符串拼接（SQL 注入，审计 S2）");
        }
    }

    @Test
    void allInsertStatementsHaveSymmetricColumnValueConditions() throws Exception {
        // 守卫来源：T2.7 浏览器回归抓到 QcmPlanMapper.insert 列块有 scheduleType 而值块缺失
        //（13 列 12 值 SQL 报错）。单测 mock mapper 测不到 SQL 形状，静态对称性检测兜底。
        java.util.List<Path> xmls = java.nio.file.Files.walk(Paths.get("src/main/resources/mapper"))
            .filter(p -> p.toString().endsWith(".xml")).collect(Collectors.toList());
        assertFalse(xmls.isEmpty());
        java.util.regex.Pattern insertPat = java.util.regex.Pattern.compile("<insert id=\"([^\"]+)\">(.*?)</insert>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern condPat = java.util.regex.Pattern.compile("<if test=\"([^\"]+)\">");
        for (Path xml : xmls) {
            String content = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
            java.util.regex.Matcher im = insertPat.matcher(content);
            while (im.find()) {
                String body = im.group(2);
                java.util.regex.Matcher t1 = java.util.regex.Pattern
                    .compile("<trim prefix=\"[(]\"[^>]*>(.*?)</trim>", java.util.regex.Pattern.DOTALL).matcher(body);
                java.util.regex.Matcher t2 = java.util.regex.Pattern
                    .compile("<trim prefix=\"values \"[^>]*>(.*?)</trim>", java.util.regex.Pattern.DOTALL).matcher(body);
                if (!(t1.find() && t2.find())) { continue; }
                java.util.Set<String> c = new java.util.TreeSet<>(), v = new java.util.TreeSet<>();
                java.util.regex.Matcher m = condPat.matcher(t1.group(1));
                while (m.find()) { c.add(m.group(1)); }
                m = condPat.matcher(t2.group(1));
                while (m.find()) { v.add(m.group(1)); }
                assertEquals(c, v, xml + " insert[" + im.group(1) + "] 列/值 if 条件不对称");
            }
        }
    }
}
