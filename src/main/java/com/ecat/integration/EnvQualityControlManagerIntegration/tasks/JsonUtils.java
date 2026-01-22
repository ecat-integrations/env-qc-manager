package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JsonUtils
 * Json工具类
 * @author caohongbo
 * @version 1.0
 * @description
 */
public class JsonUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 将非标准JSON格式字符串转换为标准JSON格式字符串
     * 例如: {key:value,nested:{nestedKey:nestedValue}} 
     * 转换为: {"key":"value","nested":{"nestedKey":"nestedValue"}}
     * 
     * @param nonStandardJson 非标准JSON格式字符串
     * @return 标准JSON格式字符串
     */
    public static String convertToStandardJson(String nonStandardJson) {
        if (nonStandardJson == null || nonStandardJson.trim().isEmpty()) {
            return nonStandardJson;
        }
        
        // 先处理键名，给所有键加上引号
        String result = nonStandardJson;
        result = quoteKeys(result);
        
        // 处理值，给字符串值加上引号
        result = quoteValues(result);
        
        return result;
    }
    
    /**
     * 给键名加上引号
     */
    private static String quoteKeys(String json) {
        // 匹配键名（冒号前的单词）
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
    
    /**
     * 给字符串值加上引号
     */
    private static String quoteValues(String json) {
        // 匹配值（冒号后的简单值，不包括对象和数组）
        // 这个正则表达式会匹配不以{,[开头，且不以引号包围的值
        Pattern valuePattern = Pattern.compile("(:\\s*)([^\\{\\[\\d\\-\"'tfn][^,}\\]]*?)(\\s*[,}])");
        Matcher valueMatcher = valuePattern.matcher(json);
        StringBuffer sb = new StringBuffer();
        
        while (valueMatcher.find()) {
            String value = valueMatcher.group(2).trim();
            // 检查是否已经是引号包围的或者是数字、布尔值、null
            if (!value.startsWith("\"") && !value.startsWith("'") && !isNumeric(value) && !isBoolean(value) && !value.equals("null")) {
                // 给值加上双引号
                valueMatcher.appendReplacement(sb, 
                    Matcher.quoteReplacement(valueMatcher.group(1) + "\"" + value + "\"" + valueMatcher.group(3)));
            } else {
                valueMatcher.appendReplacement(sb, Matcher.quoteReplacement(valueMatcher.group(0)));
            }
        }
        valueMatcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 判断字符串是否为数字
     * @param str 字符串
     * @return 是否为数字
     */
    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        // 使用正则表达式检查是否为数字（整数或小数）
        return str.matches("-?\\d+(\\.\\d+)?");
    }
    
    /**
     * 判断字符串是否为布尔值
     * @param str 字符串
     * @return 是否为布尔值
     */
    private static boolean isBoolean(String str) {
        return "true".equals(str) || "false".equals(str);
    }

    /**
     * <p>解析Map类型的JsonString 为 Map对象</p>
     * <p>
     * 支持泛型，如：Map<String, String>, Map<String, Object>等
     * 可作为公共方法
     * </p>
     * @param jsonStr String
     * @return Map<K, V>
     */
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
    
    /**
     * 解析非标准格式的Map字符串为Map对象
     * 支持类似 {resultValue:3.6916656,checkCalibLimit:10.0,stdValue:400.0,deviceValue:414.7667,checkPassLimit:5.0,params:{qualityControlType:span_check,taskType:0,parameter:SO2,taskDescription:质控任务,taskName:EnvQualityControlTask,triggerType:0,calculatedValue:0.4}} 
     * 这样的字符串
     * 
     * @param nonStandardJson 非标准格式的JSON字符串
     * @param keyType 键的类型
     * @param valueType 值的类型
     * @return 解析后的Map对象
     */
    public static <K, V> Map<K, V> parseNonStandardMap(String nonStandardJson, Class<K> keyType, Class<V> valueType) {
        try {
            if (nonStandardJson == null || nonStandardJson.trim().isEmpty()) {
                return Collections.emptyMap();
            }
            
            // 转换为标准JSON格式
            String standardJson = convertToStandardJson(nonStandardJson);
            System.out.println("转换后的标准JSON: " + standardJson); // 调试用，可删除
            
            // 使用现有的parseMap方法解析
            return parseMap(standardJson, keyType, valueType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse non-standard JSON string: " + nonStandardJson, e);
        }
    }

    /**
     * 将 JSON 字符串解析为 List<T>
     * @param jsonStr JSON 格式的字符串
     * @param elementType 列表中元素的类型，如 EnvQualityControlRecords.class
     * @return 解析后的 List<T>
     */
    public static <T> List<T> parseList(String jsonStr, Class<T> elementType) {
        try {
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(
                    jsonStr,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType)
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse JSON string to list", e);
        }
    }

    /**
     * 将 Map或List转为 JSON字符串
     * @param  obj Object
     * @return 转化后的 String
     */
    public static String toJsonString(Object obj) {
        String jsonString;
        try {
            jsonString = objectMapper.writeValueAsString(obj);
            return jsonString;
        } catch (JsonProcessingException e) {
            return "";
            // throw new RuntimeException("Failed to convert obj to json string: {}", e);
        }
    }
}