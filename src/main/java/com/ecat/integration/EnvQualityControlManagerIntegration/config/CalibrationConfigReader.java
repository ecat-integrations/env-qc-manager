package com.ecat.integration.EnvQualityControlManagerIntegration.config;

import org.yaml.snakeyaml.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 校准配置读取器
 * <p>从 EnvDeviceCalibrationIntegration.yml 读取设备映射配置</p>
 * <p>目的：避免在质控管理中硬编码设备ID，而是从校准配置中动态获取</p>
 * 
 * @version 1.0
 */
public class CalibrationConfigReader {
    
    private static final Logger logger = LoggerFactory.getLogger(CalibrationConfigReader.class);
    
    // 配置文件路径（相对于工作目录）
    private static final String CONFIG_PATH = ".ecat-data/integrations/EnvDeviceCalibrationIntegration.yml";
    
    // 气体类型到设备ID的映射
    private Map<String, String> gasToDeviceIdMap = new HashMap<>();
    
    // 气体类型到数据属性ID的映射
    private Map<String, String> gasToDataAttributeMap = new HashMap<>();
    
    /**
     * 加载配置文件
     * @return 是否加载成功
     */
    public boolean loadConfig() {
        try {
            logger.info("开始加载校准配置文件: {}", CONFIG_PATH);
            
            Yaml yaml = new Yaml();
            InputStream inputStream = new FileInputStream(CONFIG_PATH);
            Map<String, Object> config = yaml.load(inputStream);
            inputStream.close();
            
            // 从配置中提取 tested_devices
            // 配置结构：calibrations[0].tested_devices
            List<Map<String, Object>> calibrations = (List<Map<String, Object>>) config.get("calibrations");
            
            if (calibrations == null || calibrations.isEmpty()) {
                logger.error("配置文件中未找到 calibrations 节点");
                return false;
            }
            
            // 获取第一个校准配置的 tested_devices（所有校准类型应该使用相同的被测设备）
            Map<String, Object> firstCalibration = calibrations.get(0);
            Map<String, Object> testedDevices = (Map<String, Object>) firstCalibration.get("tested_devices");
            
            if (testedDevices == null) {
                logger.error("配置文件中未找到 tested_devices 节点");
                return false;
            }
            
            // 解析每个气体的设备配置
            parseTestedDevices(testedDevices);
            
            logger.info("校准配置加载成功，共解析 {} 种气体设备", gasToDeviceIdMap.size());
            logger.info("设备映射: {}", gasToDeviceIdMap);
            
            return true;
            
        } catch (Exception e) {
            logger.error("加载校准配置文件失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 解析 tested_devices 配置
     */
    private void parseTestedDevices(Map<String, Object> testedDevices) {
        // 遍历每个气体类型
        for (Map.Entry<String, Object> entry : testedDevices.entrySet()) {
            String gasType = entry.getKey(); // SO2, CO, NOx, O3
            
            if ("desc".equals(gasType)) {
                continue; // 跳过 desc 字段
            }
            
            Map<String, Object> deviceConfig = (Map<String, Object>) entry.getValue();
            
            String deviceId = (String) deviceConfig.get("device_id");
            String dataAttributeId = (String) deviceConfig.get("data_attribute_id");
            
            if (deviceId != null) {
                // 处理 NOx -> NO2 的映射
                String mappedGasType = "NOx".equals(gasType) ? "NO2" : gasType;
                
                gasToDeviceIdMap.put(mappedGasType, deviceId);
                
                if (dataAttributeId != null) {
                    gasToDataAttributeMap.put(mappedGasType, dataAttributeId);
                }
                
                logger.debug("解析设备配置: {} -> deviceId={}, dataAttribute={}", 
                    mappedGasType, deviceId, dataAttributeId);
            }
        }
    }
    
    /**
     * 根据气体类型获取设备ID
     * @param gasType 气体类型 (SO2, NO2, CO, O3)
     * @return 设备ID，如果未找到返回null
     */
    public String getDeviceId(String gasType) {
        return gasToDeviceIdMap.get(gasType);
    }
    
    /**
     * 根据气体类型获取数据属性ID
     * @param gasType 气体类型 (SO2, NO2, CO, O3)
     * @return 数据属性ID，如果未找到返回null
     */
    public String getDataAttributeId(String gasType) {
        return gasToDataAttributeMap.get(gasType);
    }
    
    /**
     * 获取所有设备ID映射（只读）
     * @return 气体类型到设备ID的映射
     */
    public Map<String, String> getAllDeviceIdMappings() {
        return new HashMap<>(gasToDeviceIdMap);
    }
    
    /**
     * 检查是否已加载配置
     * @return 是否已加载
     */
    public boolean isLoaded() {
        return !gasToDeviceIdMap.isEmpty();
    }
}

