package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 解析与序列化工具（质控模块共用）。
 */
public final class JsonUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private JsonUtils() {
    }

    public static String convertToStandardJson(String nonStandardJson) {
        if (nonStandardJson == null || nonStandardJson.trim().isEmpty()) {
            return nonStandardJson;
        }
        String result = quoteKeys(nonStandardJson);
        return quoteValues(result);
    }

    private static String quoteKeys(String json) {
        Pattern keyPattern = Pattern.compile("([{,]\\s*)([a-zA-Z_][a-zA-Z0-9_]*)(\\s*:)");
        Matcher keyMatcher = keyPattern.matcher(json);
        StringBuffer sb = new StringBuffer();
        while (keyMatcher.find()) {
            keyMatcher.appendReplacement(sb,
                    Matcher.quoteReplacement(keyMatcher.group(1) + "\"" + keyMatcher.group(2) + "\"" + keyMatcher.group(3)));
        }
        keyMatcher.appendTail(sb);
        return sb.toString();
    }

    private static String quoteValues(String json) {
        Pattern valuePattern = Pattern.compile("(:\\s*)([^\\{\\[\\d\\-\"'tfn][^,}\\]]*?)(\\s*[,}])");
        Matcher valueMatcher = valuePattern.matcher(json);
        StringBuffer sb = new StringBuffer();
        while (valueMatcher.find()) {
            String value = valueMatcher.group(2).trim();
            if (!value.startsWith("\"") && !value.startsWith("'") && !isNumeric(value) && !isBoolean(value) && !value.equals("null")) {
                valueMatcher.appendReplacement(sb,
                        Matcher.quoteReplacement(valueMatcher.group(1) + "\"" + value + "\"" + valueMatcher.group(3)));
            } else {
                valueMatcher.appendReplacement(sb, Matcher.quoteReplacement(valueMatcher.group(0)));
            }
        }
        valueMatcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return str.matches("-?\\d+(\\.\\d+)?");
    }

    private static boolean isBoolean(String str) {
        return "true".equals(str) || "false".equals(str);
    }

    public static <K, V> Map<K, V> parseMap(String jsonStr, Class<K> keyType, Class<V> valueType) {
        try {
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(
                    jsonStr,
                    objectMapper.getTypeFactory().constructMapType(Map.class, keyType, valueType)
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static <K, V> Map<K, V> parseNonStandardMap(String nonStandardJson, Class<K> keyType, Class<V> valueType) {
        try {
            if (nonStandardJson == null || nonStandardJson.trim().isEmpty()) {
                return Collections.emptyMap();
            }
            String standardJson = convertToStandardJson(nonStandardJson);
            return parseMap(standardJson, keyType, valueType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse non-standard JSON string: " + nonStandardJson, e);
        }
    }

    public static String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
