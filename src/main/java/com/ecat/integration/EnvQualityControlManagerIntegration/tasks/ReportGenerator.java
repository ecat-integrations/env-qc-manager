package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterMappingResolver;
import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.integration.EnvDataManagerIntegration.service.IRealdataService;
import com.ecat.integration.EnvQualityControlManagerIntegration.config.CalibrationConfigReader;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.*;

import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ParameterEnum.*;
import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.QualityControlTypeEnum.*;


/**
 * ReportGenerator
 * <p>质控报告生成器</p>
 * <p>可以生成：</p>
 * <ul>
 *     <li>仪器运行状况检查/校准记录表</li>
 *     <li>仪器多点校准记录表</li>
 *     <li>仪器精密度审核记录表</li>
 *     <li>仪器准确度审核记录表</li>
 *     <li>氮氧化物转换效率测试记录表</li>
 *     <li>臭氧校准设备量值传递记录表</li>
 * </ul>
 * <p>结果说明：</p>
 * <table border="1">
 *     <tr>
 *         <td width="40%">GenZeroAndSpanReport</td>
 *         <td width="100%">支持, 零点和跨度检查，以最近的两个零和跨作为一组，存在单零（发生校准）、单跨（发生校准）的情况</td>
 *     </tr>
 *     <tr>
 *         <td>GenMultiCheckReport</td>
 *         <td>支持</td>
 *     </tr>
 *     <tr>
 *         <td>GenPrecisionReport</td>
 *         <td>支持</td>
 *     </tr>
 *     <tr>
 *         <td>GenAccuracyReport</td>
 *         <td>支持</td>
 *     </tr>
 *     <tr>
 *         <td>GenConversionReport</td>
 *         <td>仅支持 NOx</td>
 *     </tr>
 *     <tr>
 *         <td>GenCalibrationReport</td>
 *         <td>不支持</td>
 *     </tr>
 * </table>
 * @author caohongbo
 */
public class ReportGenerator {

    private EcatCore core;
    protected EcatCoreRuoyiIntegration mry;

    private static final Logger logger = LoggerFactory.getLogger(ReportGenerator.class);

    private IEnvQualityControlRecordsService envQualityControlRecordsService;
    
    // 设备映射配置（从校准配置文件加载）
    private Map<String, String> gasToDeviceIdMap = new HashMap<>();
    private CalibrationConfigReader configReader;
    private final ParameterMappingResolver parameterMappingResolver = ParameterMappingResolver.getInstance();

    public String reportType;
    // 存储报告最终结果
    @Getter
    public EnvQualityControlReport report;

    public ReportGenerator() {
    }

    public ReportGenerator(EcatCore core) {
        this.core = core;
        loadDeviceMapping();
    }
    
    /**
     * 从校准配置文件加载设备映射
     * <p>读取 EnvDeviceCalibrationIntegration.yml 中的 tested_devices 配置</p>
     * <p>避免硬编码设备ID，支持不同厂商的设备（如 esa-so2 或 sms-so2）</p>
     */
    private void loadDeviceMapping() {
        try {
            configReader = new CalibrationConfigReader();
            boolean loaded = configReader.loadConfig();
            
            if (loaded) {
                gasToDeviceIdMap = configReader.getAllDeviceIdMappings();
                logger.info("设备映射加载成功: {}", gasToDeviceIdMap);
            } else {
                logger.warn("设备映射加载失败，将使用默认映射");
                loadDefaultDeviceMapping();
            }
        } catch (Exception e) {
            logger.error("加载设备映射异常: {}", e.getMessage(), e);
            loadDefaultDeviceMapping();
        }
    }

    protected String getStdGasConcentration(String gasType) {
        Optional<String> valueOpt = parameterMappingResolver.getAttributeValue(
                "std_gas_concentration",
                gasType,
                null,
                id -> core.getDeviceRegistry().getDeviceByID(id));
        return valueOpt.orElse("");
    }
    
    /**
     * 加载默认设备映射（降级方案）
     * <p>当配置文件不可用时使用默认的 esa 设备</p>
     */
    private void loadDefaultDeviceMapping() {
        gasToDeviceIdMap.put("SO2", "esa-so2");
        gasToDeviceIdMap.put("NO2", "esa-no2");
        gasToDeviceIdMap.put("O3", "esa-o3");
        gasToDeviceIdMap.put("CO", "esa-co");
        logger.info("使用默认设备映射: {}", gasToDeviceIdMap);
    }

    /**
     * 安全转换 Number 类型到 Float
     * JSON 解析时数字可能是 Double、Float、Integer 等类型
     * @param obj 待转换的对象
     * @return Float 值，如果无法转换则返回 null
     */
    protected Float convertToFloat(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Number) {
            return ((Number) obj).floatValue();
        }
        if (obj instanceof String) {
            try {
                return Float.parseFloat((String) obj);
            } catch (NumberFormatException e) {
                logger.warn("Failed to convert string to float: " + obj, e);
                return null;
            }
        }
        logger.warn("Unexpected type for float conversion: " + obj.getClass().getName());
        return null;
    }

    /**
     * 安全转换 List 到 List<Float>
     * JSON 解析时列表中的数字可能是 Double 类型
     * @param obj 待转换的对象
     * @return List<Float>，如果无法转换则返回空列表
     */
    protected List<Float> convertToFloatList(Object obj) {
        List<Float> result = new ArrayList<>();
        if (obj == null) {
            return result;
        }
        if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                Float floatValue = convertToFloat(item);
                if (floatValue != null) {
                    result.add(floatValue);
                }
            }
        }
        return result;
    }

    /**
     * 根据气态参数获取设备信息
     * <p>从校准配置文件中动态获取设备ID，支持不同厂商设备</p>
     * <p>例如：SO2 可能对应 esa-so2 或 sms-so2，取决于实际部署配置</p>
     * 
     * @param param 气态参数 (SO2, NO2, CO, O3)
     * @return 设备对象，如果未找到返回 sms-qc 设备（降级方案）
     */
    public DeviceBase getDeviceInfo(String param) {
        // 从配置映射中获取设备ID
        String deviceId = gasToDeviceIdMap.get(param);
        
        // 如果未找到映射，使用默认的质控系统设备
        if (deviceId == null) {
            logger.warn("未找到气体 {} 的设备映射，使用默认质控设备 sms-qc", param);
            deviceId = "sms-qc";
        }

        logger.debug("获取设备信息: {} -> {}", param, deviceId);
        DeviceRegistry deviceRegistry = core.getDeviceRegistry();
        DeviceBase device = deviceRegistry.getDeviceByID(deviceId);
        
        if (device == null) {
            logger.error("未找到设备: {}, 请检查设备配置", deviceId);
        }
        
        return device;
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param records 质控记录
     * @return report 质控报告
     */
    private EnvQualityControlReport parseRecordToReport(List<EnvQualityControlRecords> records) {
        throw new UnsupportedOperationException();
    }

    private EnvQualityControlReport parseRecordToReport(EnvQualityControlRecords record) {
        throw new UnsupportedOperationException();
    }

    /**
     * 组装报告数据
     * @return
     */
    public Map<String,  Object> constructReportContent() {
        throw new UnsupportedOperationException();
    }

    /**
     * 生成某段时间的报告
     * @param startTime 开始时间
     * @param endTime  结束时间
     */
    public List<EnvQualityControlReport> generate(Date startTime, Date endTime) {
        // 查询质控记录表，获取某段时间内的质控记录，遍历成功完成的记录，存入质控报告表，注意零跨的特殊处理
        if (mry == null) {
            mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry().getIntegration("integration-ecat-core-ruoyi");
        }
        envQualityControlRecordsService = mry.getSpringBean(IEnvQualityControlRecordsService.class);
        List<EnvQualityControlRecords> recordResults = envQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(startTime, endTime, null, 2L);
        logger.info("ReportGenerator.generate.selectEnvQualityControlRecordsByTypeTime("+startTime +","+ endTime+")"
                + "recordResults -> " + recordResults);

        List<EnvQualityControlReport> reports = new ArrayList<>();
        if (recordResults.isEmpty()) return reports;

        // 零跨时间线
        Map<String, List<EnvQualityControlRecords>> zeroSpanLineMap = new HashMap<>();
        zeroSpanLineMap.put(ParameterEnum.SO2.getCode(), new ArrayList<>());
        zeroSpanLineMap.put(ParameterEnum.NO2.getCode(), new ArrayList<>());
        zeroSpanLineMap.put(ParameterEnum.O3.getCode(), new ArrayList<>());
        zeroSpanLineMap.put(CO.getCode(), new ArrayList<>());

        // 人工核查时间线
        Map<String, List<EnvQualityControlRecords>> auditSpanLineMap = new HashMap<>();
        auditSpanLineMap.put(ParameterEnum.SO2.getCode(), new ArrayList<>());
        auditSpanLineMap.put(ParameterEnum.NO2.getCode(), new ArrayList<>());
        auditSpanLineMap.put(ParameterEnum.O3.getCode(), new ArrayList<>());
        auditSpanLineMap.put(CO.getCode(), new ArrayList<>());

        // 遍历利质控记录列表
        for (EnvQualityControlRecords recordResult : recordResults) {
            try {
                // 将成功完成的记录存入质控报告表
                // 默认只取成功的记录
                // if (recordResult.getExecutionStatus().equals(ExecutionStatusEnum.SUCCESS.getCode())) {}
                // else { do nothing }

                // 未成功的质控记录，不生成质控报告，而在质控记录里溯查原因，并记录在质控记录表里
                if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.ZERO_CHECK.getCode()) ||
                        recordResult.getQualityControlType().equals(QualityControlTypeEnum.SPAN_CHECK.getCode())) {
                    // 分参数 放入零跨时间线，等待滑动时间窗口处理零跨聚合，后续实现时间线处理...
                    String param = recordResult.getParameter();
                    // 如果param属于zeroSpanLineMap的key,则将recordResult加入到zeroSpanLine
                    if (zeroSpanLineMap.containsKey(param)) {
                        zeroSpanLineMap.get(param).add(recordResult);
                    }
                    // throw new UnsupportedOperationException("暂不支持零跨聚合");

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.MULTI_CHECK.getCode())) {
                    GenMultiCheckReport genReport = new GenMultiCheckReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.PRECISION_CHECK.getCode())) {
                    GenPrecisionReport genReport = new GenPrecisionReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.ACCURACY_CHECK.getCode())) {
                    GenAccuracyReport genReport = new GenAccuracyReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.CONVERSION_CHECK.getCode())) {
                    GenConversionReport genReport = new GenConversionReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals("calibration_check")) {
                    GenTransferAndTraceReport genReport = new GenTransferAndTraceReport(core, recordResult);
                    reports.add(genReport.getReport());

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.AUDIT_SPAN_CHECK.getCode())) {
                    // 目的是将当天的所有人工核查聚合到一张报告中
                    String param = recordResult.getParameter();
                    if (auditSpanLineMap.containsKey(param)) {
                        auditSpanLineMap.get(param).add(recordResult);
                    }

                } else {
                    throw new UnsupportedOperationException("暂不支持该类型质控");
                }
                // reportResult.setReportContent(reportContent);
            } catch (UnsupportedOperationException e) {
                System.out.println("Generate report failed for record: " + recordResult + ", reason: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 遍历zeroSpanLineMap的各个参数，处理零跨时间线上的零点和跨度质控
        for (Map.Entry<String, List<EnvQualityControlRecords>> entry : zeroSpanLineMap.entrySet()) {
            List<EnvQualityControlRecords> zeroSpanLine = entry.getValue();
            // 如果零跨时间线不为空，则进行零跨处理
            if (!zeroSpanLine.isEmpty()) {
                GenZeroAndSpanReport genReport = new GenZeroAndSpanReport(core, zeroSpanLine);
                reports.add(genReport.getReport());
            }
        }

        // 遍历auditSpanLineMap的各个参数，处理人工核查时间线上的所有跨度质控
        for (Map.Entry<String, List<EnvQualityControlRecords>> entry : auditSpanLineMap.entrySet()) {
            List<EnvQualityControlRecords> auditSpanLine = entry.getValue();
            if (!auditSpanLine.isEmpty()) {
                GenAuditSpanReport genReport = new GenAuditSpanReport(core, auditSpanLine);
                reports.add(genReport.getReport());
            }
        }


        return reports;
    }

}


////////////////////////////////////// SON CLASSES //////////////////////////////////////
/**
 * GenZeroAndSpanReport
 * <p>生成仪器运行状况检查/校准记录表</p>
 * {@code @description} 生成零点及跨度检测报表
 * 优先实现如下需求:
 *     获取最新的一条零点检查记录 + 跨度检查记录
 *     判断是否进行了校准，均没有校准，则返回空表？ 若进行了零点校准，则将过程及返回值记录在表格中。
 * @author caohongbo
 * @version 1.0
 */
class GenZeroAndSpanReport extends ReportGenerator {

    public SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private EcatCore core;

    private IRealdataService realdataService;

    @Getter
    private ZeroAndSpanReport report;

    public final static String FULL_SPAN = "500ppb";
    public final static String FULL_SPAN_CO = "50ppm";

    /**
     * 实际执行此方法 生成报告
     * @param records
     */
    public GenZeroAndSpanReport(EcatCore core, List<EnvQualityControlRecords> records) {
        super(core);
        this.core = core;
        report = new ZeroAndSpanReport();
        report = parseRecordToReport(records);
        report.setComponent(ReportTypeEnum.ZERO_SPAN.getComponent());

        // 用于报告展示的主要内容
        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);
    }

    /**
     * 获取关键参数
     * 查询实时数据表，获取某段时间内的数据
     * @example
     * <pre>
     *  keyParameters.add(new HashMap<String, Object>() {{
     *      put("tName", "Flow");
     *      put("tValue", "1.2L/min");
     *      put("tRange", "0~100L/min");
     *      put("tRemark", "处理记录111");
     *  }});
     *  keyParameters.add(new HashMap<String, Object>() {{
     *      put("tName", "GasPressure");
     *      put("tValue", "50.0psi");
     *      put("tRange", "-50~50.0psi");
     *      put("tRemark", "处理记录222");
     *  }});
     * @param beginPickTime 开始时间
     * @param  endPickTime 结束时间
     */
    private List< Map<String, Object> > queryKeyParameters(Date beginPickTime, Date endPickTime, String param, String deviceId) {
        // xxx监测仪 | esa-co | esa-no2 | esa-o3 | esa-so2 |
        List<String> types = new ArrayList<>();
        types.add("Flow");  // 流量
        types.add("SampleP");  // 采样压力
        types.add("GasPressure");  // 气体压力
        types.add("GasT");  // 气体温度
        types.add("InternalTemp");  // 内部温度
        types.add(param + "_concentration");  // XX浓度

        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("beginPickTime", beginPickTime);
        queryParams.put("endPickTime", endPickTime);
        queryParams.put("pids", types);
        queryParams.put("sid", deviceId);

        // 实时数据获取关键参数，每种参数取一条
        if (mry == null) {
            mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry().getIntegration("integration-ecat-core-ruoyi");
        }
        realdataService = mry.getSpringBean(IRealdataService.class);
        List< Map<String, Object> > keyDatas = realdataService.selectDistinctTypeData(queryParams);

        List< Map<String, Object> > keyParameters = new ArrayList<>();
        for (Map<String, Object> keyData : keyDatas) { 
            keyParameters.add(new HashMap<String, Object>() {{
                put("tName", keyData.getOrDefault("pn", ""));
                put("tValue", keyData.getOrDefault("value", "")+ " " +keyData.getOrDefault("unit_name", ""));
                put("tRange", keyData.getOrDefault("range", ""));
                put("tRemark", keyData.getOrDefault("remark", ""));
            }});
        }
        return keyParameters;
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param records 质控记录
     * @return report 质控报告
     */
    private ZeroAndSpanReport parseRecordToReport(List<EnvQualityControlRecords> records) {
        EnvQualityControlRecords records_0 = records.get(0);

        report.setReportDate(records_0.getCreateTime()); // 报告日期 默认是质控记录开始时间
        report.setFiler(records_0.getCreatedBy());  // 填表人 默认是质控记录创建者
        report.setReviewer(records_0.getUpdateBy());  // 复核人 默认是质控记录更新者
        report.setCreatedBy(records_0.getCreatedBy());
        report.setUpdatedBy(records_0.getUpdateBy());
        report.setGasType(records_0.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);  // 标气来源 暂为标气展示类型
        DeviceBase device = getDeviceInfo(param);
        report.setInstrumentName(device.getName());
        report.setReportName(device.getName() + report.getReportName());
        report.setInstrumentNo(device.getSn());
        report.setInstrumentNameAndNo(device.getName() + device.getSn());
        // 获取钢瓶气浓度
        String gasConcentration = getStdGasConcentration(param);
        report.setGasConcentration(gasConcentration == null ? "" : gasConcentration);

        String reportNote = "";
        for (EnvQualityControlRecords record : records) {
            reportNote += record.getExecutionLog() + "\n";
            if (record.getQualityControlType().equals(ZERO_CHECK.getCode())) {
                // 从record中获取零点数据
                report.setZeroStartTime(sdf.format(record.getStartTime()));
                report.setZeroEndTime(sdf.format(record.getEndTime()));
                // 解析record.getExecutionLog(),将JsonString转换成Map
                Map<String, Object> executionLogMap = JsonUtils.parseNonStandardMap(record.getExecutionLog(), String.class, Object.class);
                // {"resultValue":492.255,"checkCalibLimit":2500.0,"stdValue":0.0,"deviceValue":-492.255,"checkPassLimit":1000.0}

                if(record.getParameter().equals("4")) {
                    try {
                        report.setZeroStandardConcentration((Double)executionLogMap.get("stdValue")/1000 + "");
                        report.setZeroDisplayResponse((Double)executionLogMap.get("deviceValue")/1000 + "");
                        report.setZeroCalibrationResponse((Double)executionLogMap.get("stdValue")/1000 + "");
                        report.setZeroDriftResult((Double)executionLogMap.get("resultValue")/1000 + "");
                    }catch (Exception e){
                        report.setZeroStandardConcentration(executionLogMap.get("stdValue") + "");
                        report.setZeroDisplayResponse(executionLogMap.get("deviceValue") + "");
                        report.setZeroCalibrationResponse(executionLogMap.get("stdValue") + "");
                        report.setZeroDriftResult(executionLogMap.get("resultValue") + "");
                    }
                }else{
                    report.setZeroStandardConcentration(executionLogMap.get("stdValue") + "");
                    report.setZeroDisplayResponse(executionLogMap.get("deviceValue") + "");
                    report.setZeroCalibrationResponse(executionLogMap.get("stdValue") + "");
                    report.setZeroDriftResult(executionLogMap.get("resultValue") + "");
                }
                  // TODO 暂时为标准浓度值，实际取稳定6分钟，其中的一个值)。
                String zeroCalibrationResult = (boolean) executionLogMap.getOrDefault("isPass", false) ? "合格": "不合格";
                report.setZeroCalibrationResult(zeroCalibrationResult);

            } else if (record.getQualityControlType().equals(SPAN_CHECK.getCode())){
                // 从record中获取跨度数据
                report.setSpan80StartTime(sdf.format(record.getStartTime()));
                report.setSpan80EndTime(sdf.format(record.getEndTime()));
                // 解析record.getExecutionLog(),将JsonString转换成Map
                Map<String, Object> resultEvaluation= JsonUtils.parseNonStandardMap(record.getExecutionLog(), String.class, Object.class);
                // {"resultValue":1.1075928,"checkCalibLimit":10.0,"stdValue":40000.0,"deviceValue":39556.96,"checkPassLimit":5.0}
                report.setSpan80DriftResult(resultEvaluation.get("resultValue") + "");
                if(record.getParameter().equals("4")){
                    try {
                        report.setSpan80StandardConcentration((Double)resultEvaluation.get("stdValue")/1000 + "");
                        report.setSpan80DisplayResponse((Double)resultEvaluation.get("deviceValue")/1000 + "");
                        report.setSpan80CalibrationResponse((Double)resultEvaluation.get("stdValue")/1000 + "");
                    }catch (Exception e){
                        report.setSpan80StandardConcentration(resultEvaluation.get("stdValue") + "");
                        report.setSpan80DisplayResponse(resultEvaluation.get("deviceValue") + "");
                        report.setSpan80CalibrationResponse(resultEvaluation.get("stdValue") + "");
                    }
                }else{
                    report.setSpan80StandardConcentration(resultEvaluation.get("stdValue") + "");
                    report.setSpan80DisplayResponse(resultEvaluation.get("deviceValue") + "");
                    report.setSpan80CalibrationResponse(resultEvaluation.get("stdValue") + "");
                }
                  // TODO 暂时为标准浓度值，实际取稳定6分钟，其中的一个值)。
                String spanCalibrationResult = (boolean) resultEvaluation.getOrDefault("isPass", false) ? "合格": "不合格";
                report.setSpanCalibrationResult(spanCalibrationResult);
            } else {
                throw new RuntimeException("Invalid report type for ZeroAndSpanReport: " + record.getQualityControlType());
            }
        }
        report.setReportNote(reportNote);   // 备注 默认是质控记录结果评价

        // 关键参数
        List< Map<String, Object> > keyParameters = queryKeyParameters(records_0.getStartTime(), records_0.getEndTime(), param, device.getId());
        report.setKeyParameters(keyParameters);

        return report;
    }
    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();

        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrument_info = new HashMap<>();
        // 设备名称及编号
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        // 校准日期
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        // 标气来源及编号
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        // 标气浓度
        instrument_info.put("gas_concentration", report.getGasConcentration());
        reportContent.put("instrument_info", instrument_info);
        // 零点和跨度结果
        List<Map<String, Object>> calibrationPoints = new ArrayList<>();
        calibrationPoints.add(
                new HashMap<String, Object>() {{
                    put("point_name", "零点");
                    put("start_time", report.getZeroStartTime());
                    put("end_time", report.getZeroEndTime());
                    put("standard_concentration", report.getZeroStandardConcentration());
                    put("display_value", report.getZeroDisplayResponse());
                    put("calibration_value", report.getZeroCalibrationResponse());
                }}
        );
        calibrationPoints.add(
                new HashMap<String, Object>() {{
                    put("point_name", "满量程的80%");
                    put("start_time", report.getSpan80StartTime());
                    put("end_time", report.getSpan80EndTime());
                    put("standard_concentration", report.getSpan80StandardConcentration());
                    put("display_value", report.getSpan80DisplayResponse());
                    put("calibration_value", report.getSpan80CalibrationResponse());
                }}
        );
        reportContent.put("calibration_points", calibrationPoints);
        String fullSpan = report.getGasType().equals(CO.getCode())? FULL_SPAN_CO : FULL_SPAN;
        reportContent.put("full_span", fullSpan);
        reportContent.put("zero_drift_result", report.getZeroDriftResult());
        reportContent.put("span_80_drift_result", report.getSpan80DriftResult());
        reportContent.put("key_parameters", report.getKeyParameters());
        reportContent.put("span_calibration_result", report.getSpanCalibrationResult());
        reportContent.put("zero_calibration_result", report.getZeroCalibrationResult());

        return reportContent;
    }
}

/**
 * GenMultiCheckReport
 * <p>生成仪器多点校准记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenMultiCheckReport extends ReportGenerator{

    private EcatCore core;

    @Getter
    private MultiCheckReport report;

    private EnvQualityControlRecords record;

    public GenMultiCheckReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        report = new MultiCheckReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.MULTI.getComponent());

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private MultiCheckReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime()); // 报告日期 默认是质控记录开始时间
        report.setFiler(record.getCreatedBy());  // 填表人 默认是质控记录创建者
        report.setReviewer(record.getUpdateBy());  // 复核人 默认是质控记录更新者
        report.setCreatedBy(record.getCreatedBy());
        report.setUpdatedBy(record.getUpdateBy());
        report.setReportNote(record.getResultEvaluation()); // 备注 默认是质控记录结果评价
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);
        DeviceBase device = getDeviceInfo(param);
        report.setInstrumentName(device.getName());
        report.setInstrumentNo(device.getSn());
        report.setInstrumentNameAndNo(device.getName() + device.getSn());
        report.setReportName(device.getName() + report.getReportName());
        // 从质控记录的日志ExecutionLog中解析质控报表数据
        // {"correlation":-0.0421348,"check_b_scope":5.0,"intercept":3.078246,"deviceValues":[0.4,6.05,3.3,2.8,2.7,2.7],"check_a_max":1.05,"stdValues":[0.0,50.0,100.0,200.0,300.0,400.0],"check_r_min":0.999,"check_a_min":0.95,"slope":-4.947389E-4}
        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = JsonUtils.parseMap(executionLog, String.class, Object.class);
        report.setFormula("Y = aX + b");  // 公式
        report.setA(executionLogMap.get("slope") + "");
        report.setB(executionLogMap.get("intercept") + "");
        report.setR(executionLogMap.get("correlation") + "");

        List<Float> gasConcentrationInput = convertToFloatList(executionLogMap.get("stdValues"));
        report.setGasConcentrationsInput(gasConcentrationInput);
        List<Float> instrumentResponse = convertToFloatList(executionLogMap.get("deviceValues"));
        report.setInstrumentResponses(instrumentResponse);

        String calibrationResult = (boolean) executionLogMap.getOrDefault("isPass", false) ? "合格": "不合格";
        report.setCalibrationResult(calibrationResult);

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrument_info = new HashMap<>();
        // 设备名称及编号
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        // 校准日期
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        // 标气来源及编号
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        // 标气浓度
        instrument_info.put("gas_concentration", report.getGasConcentration());
        reportContent.put("instrument_info", instrument_info);

        // 通入仪器标气浓度
        reportContent.put("gas_concentrations_input", report.getGasConcentrationsInput());
        // 仪器响应值
        reportContent.put("instrument_responses", report.getInstrumentResponses());

        // 校准曲线
        Map<String, String> calibration_curve = new HashMap<>();
        calibration_curve.put("formula", report.getFormula()); // 公式
        calibration_curve.put("a", report.getA());  // 斜率
        calibration_curve.put("b", report.getB());  // 截距
        calibration_curve.put("r", report.getR());  // 相关系数
        reportContent.put("calibration_curve", calibration_curve);
        // 校准结果
        reportContent.put("calibration_result", report.getCalibrationResult());

        // 填表人
        reportContent.put("remark", report.getReportNote());
        // 复核人
        reportContent.put("reviewer", report.getReviewer());

        return reportContent;
    }
}

/**
 * GenPrecisionReport
 * <p>生成仪器精密度审核记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenPrecisionReport extends ReportGenerator{

    private EcatCore core;

    @Getter
    private PrecisionReport report;

    public GenPrecisionReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        report = new PrecisionReport();
        report = parseRecordToReport(record);
        report.setComponent(ReportTypeEnum.PRECISION.getComponent());

        Map<String,  Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);

    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private PrecisionReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime()); // 报告日期 默认是质控记录开始时间
        report.setFiler(record.getCreatedBy());  // 填表人 默认是质控记录创建者
        report.setReviewer(record.getUpdateBy());  // 复核人 默认是质控记录更新者
        report.setCreatedBy(record.getCreatedBy());
        report.setUpdatedBy(record.getUpdateBy());
        report.setReportNote(record.getResultEvaluation()); // 备注 默认是质控记录结果评价
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);
        DeviceBase device = getDeviceInfo(param);
        report.setInstrumentName(device.getName());
        report.setInstrumentNo(device.getSn());
        report.setInstrumentNameAndNo(device.getName() + device.getSn());
        report.setReportName(device.getName() + report.getReportName());
        // 从质控记录的日志ExecutionLog中解析质控报表数据
        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = JsonUtils.parseMap(executionLog, String.class, Object.class);
        Float precision = convertToFloat(executionLogMap.get("precision"));  // 精密度 相对标准偏差
        Float mean = convertToFloat(executionLogMap.get("mean"));  // 平均值
        Float standardDeviation = convertToFloat(executionLogMap.get("standardDeviation"));  // 标准偏差
        Float devicesStdGas = convertToFloat(executionLogMap.get("devicesStdGas"));  //通入设备的标气浓度
        Float checkRsd20Max = convertToFloat(executionLogMap.get("checkRsd20Max"));  //20%满量程的相对标准偏差最大值
        List<Float> instrumentResponse = convertToFloatList(executionLogMap.get("deviceValues"));
        report.setInstrumentResponses(instrumentResponse);
        report.setRelativeStandardDeviation(precision);

        String calibrationResult = (boolean) executionLogMap.getOrDefault("isPass", false) ? "合格": "不合格";
        report.setCalibrationResult(calibrationResult);

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrument_info = new HashMap<>();
        // 设备名称及编号
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        // 校准日期
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        // 标气来源及编号
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        // 标气浓度
        instrument_info.put("gas_concentration", report.getGasConcentration());
        reportContent.put("instrument_info", instrument_info);

        // 通入仪器标气浓度
        reportContent.put("gas_concentrations_input", report.getGasConcentrationsInput());
        // 仪器响应值
        reportContent.put("instrument_responses", report.getInstrumentResponses());
        // 相对标准偏差（精密度）
        reportContent.put("relative_standard_deviation", report.getRelativeStandardDeviation());

        return reportContent;

    }
}

/**
 * GenAccuracyReport
 * <p>生成仪器准确度审核记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenAccuracyReport extends ReportGenerator {

    private EcatCore core;

    @Getter
    private AccuracyReport report;

    public GenAccuracyReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        report = new AccuracyReport();
        report = parseRecordToReport(record);

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);

    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private AccuracyReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime()); // 报告日期 默认是质控记录开始时间
        report.setFiler(record.getCreatedBy());  // 填表人 默认是质控记录创建者
        report.setReviewer(record.getUpdateBy());  // 复核人 默认是质控记录更新者
        report.setCreatedBy(record.getCreatedBy());
        report.setUpdatedBy(record.getUpdateBy());
        report.setReportNote(record.getResultEvaluation()); // 备注 默认是质控记录结果评价
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);
        DeviceBase device = getDeviceInfo(param);
        report.setInstrumentName(device.getName());
        report.setInstrumentNo(device.getSn());
        report.setInstrumentNameAndNo(device.getName() + device.getSn());
        report.setReportName(device.getName() + report.getReportName());
        // 从质控记录的日志ExecutionLog中解析质控报表数据
        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = JsonUtils.parseMap(executionLog, String.class, Object.class);
        report.setFormula("Y = aX + b");  // 公式
        report.setA(executionLogMap.get("slope") + "");
        report.setB(executionLogMap.get("intercept") + "");
        report.setR(executionLogMap.get("correlation") + "");
        report.setAverageRelativeError(executionLogMap.get("relativeError") + "");

        List<Float> gasConcentrationInput = convertToFloatList(executionLogMap.get("stdValues"));
        report.setGasConcentrationsInput(gasConcentrationInput);
        List<Float> instrumentResponse = convertToFloatList(executionLogMap.get("deviceValues"));
        report.setInstrumentResponses(instrumentResponse);

        String calibrationResult = (boolean) executionLogMap.getOrDefault("isPass", false) ? "合格": "不合格";
        report.setCalibrationResult(calibrationResult);

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrument_info = new HashMap<>();
        // 设备名称及编号
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        // 校准日期
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        // 标气来源及编号
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        // 标气浓度
        instrument_info.put("gas_concentration", report.getGasConcentration());
        reportContent.put("instrument_info", instrument_info);

        // 通入仪器标气浓度
        reportContent.put("gas_concentrations_input", report.getGasConcentrationsInput());
        // 仪器响应值
        reportContent.put("instrument_responses", report.getInstrumentResponses());

        // 校准曲线
        Map<String, String> calibration_curve = new HashMap<>();
        calibration_curve.put("formula", report.getFormula()); // 公式
        calibration_curve.put("a", report.getA());  // 斜率
        calibration_curve.put("b", report.getB());  // 截距
        calibration_curve.put("r", report.getR());  // 相关系数
        reportContent.put("calibration_curve", calibration_curve);
        // 平均相对误差
        reportContent.put("average_relative_error", report.getAverageRelativeError());
        // 校准结果
        reportContent.put("calibration_result", report.getCalibrationResult());

        // 填表人
        reportContent.put("remark", report.getReportNote());
        // 复核人
        reportContent.put("reviewer", report.getReviewer());

        return reportContent;
    }

}

/**
 * GenConversionReport
 * <p>生成氮氧化物转换效率测试记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenConversionReport extends ReportGenerator {

    private EcatCore core;

    @Getter
    private ConversionReport report;

    public GenConversionReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        report = new ConversionReport();
        report = parseRecordToReport(record);

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);

    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param record 质控记录
     * @return report 质控报告
     */
    private ConversionReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime()); // 报告日期 默认是质控记录开始时间
        report.setFiler(record.getCreatedBy());  // 填表人 默认是质控记录创建者
        report.setReviewer(record.getUpdateBy());  // 复核人 默认是质控记录更新者
        report.setCreatedBy(record.getCreatedBy());
        report.setUpdatedBy(record.getUpdateBy());
        report.setReportNote(record.getResultEvaluation()); // 备注 默认是质控记录结果评价
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);
        DeviceBase device = getDeviceInfo(param);
        report.setInstrumentName(device.getName());
        report.setInstrumentNo(device.getSn());
        report.setInstrumentNameAndNo(device.getName() + device.getSn());
        report.setReportName(device.getName() + report.getReportName());
        
        // 获取钢瓶气浓度（NO 和 NO2）
        List<String> gasConcentrations = new ArrayList<>(2);
        gasConcentrations.add(getStdGasConcentration("NO"));
        gasConcentrations.add(getStdGasConcentration("NO2"));
        report.setGasConcentration(gasConcentrations);
        
        // 从质控记录的日志ExecutionLog中解析质控报表数据
        String executionLog = record.getExecutionLog();
        Map<String, Object> executionLogMap = JsonUtils.parseMap(executionLog, String.class, Object.class);
        
        // 使用NO2进行转换效率测试
        report.setOrigNo2Datas(convertToFloatList(executionLogMap.get("remNo2Datas")));
        report.setOrigNo2Avg(convertToFloat(executionLogMap.get("origNo2Avg")));
        report.setNo2Efficiency(convertToFloat(executionLogMap.get("efficiency2")));
        
        // 使用NO进行转换效率测试
        report.setRemNoDatas(convertToFloatList(executionLogMap.get("remNoDatas")));
        report.setRemNoxDatas(convertToFloatList(executionLogMap.get("remNoxDatas")));
        report.setOrigNoDatas(convertToFloatList(executionLogMap.get("origNoDatas")));
        report.setOrigNoxDatas(convertToFloatList(executionLogMap.get("origNoxDatas")));
        report.setRemNoAvg(convertToFloat(executionLogMap.get("remNoAvg")));
        report.setRemNoxAvg(convertToFloat(executionLogMap.get("remNoxAvg")));
        report.setOrigNoAvg(convertToFloat(executionLogMap.get("origNoAvg")));
        report.setOrigNoxAvg(convertToFloat(executionLogMap.get("origNoxAvg")));
        report.setNoEfficiency(executionLogMap.get("efficiency") + "");

        String calibrationResult = (boolean) executionLogMap.getOrDefault("isPass", false) ? "合格": "不合格";
        report.setCalibrationResult(calibrationResult);

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrument_info = new HashMap<>();
        // 设备名称及编号
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        // 校准日期
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        // 标气来源及编号
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        // 标气浓度
        // instrument_info.put("gas_concentration", report.getGasConcentration());
        if (report.getGasConcentration() != null && report.getGasConcentration().size() > 1) {
            instrument_info.put("no_concentration", report.getGasConcentration().get(0));
            instrument_info.put("no2_concentration", report.getGasConcentration().get(1));
        } else if (report.getGasConcentration().size() > 0) {
            instrument_info.put("no_concentration", report.getGasConcentration().get(0));
            instrument_info.put("no2_concentration", "");
        } else {
            instrument_info.put("no_concentration", "");
            instrument_info.put("no2_concentration", "");
        }
        reportContent.put("instrument_info", instrument_info);

        // 使用NO2进行转换效率测试，仪器响应值 TODO: 目前暂无此数据，实际质控用的不多，暂不处理
        reportContent.put("orig_no2_datas", report.getOrigNo2Datas());
        reportContent.put("orig_no2_avg", report.getOrigNo2Avg());
        // NO2转换效率
        reportContent.put("no2_efficiency", report.getNo2Efficiency());

        // 使用NO标气进行转换效率测试，仪器响应值
        reportContent.put("rem_no_datas", report.getRemNoDatas());
        reportContent.put("rem_no_avg", report.getRemNoAvg());
        reportContent.put("rem_nox_datas", report.getRemNoxDatas());
        reportContent.put("rem_nox_avg", report.getRemNoxAvg());
        reportContent.put("orig_no_datas", report.getOrigNoDatas());
        reportContent.put("orig_no_avg", report.getOrigNoAvg());
        reportContent.put("orig_nox_datas", report.getOrigNoxDatas());
        reportContent.put("orig_nox_avg", report.getOrigNoxAvg());
        // 转换效率
        reportContent.put("efficiency", report.getNoEfficiency());
        // 结果评价
        reportContent.put("result_evaluation", report.getReportNote());
        // 填表人和复核人
        reportContent.put("filer", report.getFiler());
        reportContent.put("reviewer", report.getReviewer());

        return reportContent;
    }
}

/**
 * GenTransferAndTraceReport
 * <p>生成臭氧校准设备量值传递记录表</p>
 * @author caohongbo
 * @version 1.0
 */
class GenTransferAndTraceReport extends ReportGenerator {

    private EcatCore core;

    @Getter
    private TransferAndTraceReport report;

    public GenTransferAndTraceReport(EcatCore core, EnvQualityControlRecords record) {
        super(core);
        this.core = core;
        report = new TransferAndTraceReport();
        report = parseRecordToReport(record);

        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);

    }

    /**
     * 解析质控记录中的用于报告的数据
     *
     * @param record 质控记录
     * @return report 质控报告
     */
    private TransferAndTraceReport parseRecordToReport(EnvQualityControlRecords record) {
        report.setReportDate(record.getStartTime()); // 报告日期 默认是质控记录开始时间
        report.setFiler(record.getCreatedBy());  // 填表人 默认是质控记录创建者
        report.setReviewer(record.getUpdateBy());  // 复核人 默认是质控记录更新者
        report.setCreatedBy(record.getCreatedBy());
        report.setUpdatedBy(record.getUpdateBy());
        report.setReportNote(record.getResultEvaluation()); // 备注 默认是质控记录结果评价
        report.setGasType(record.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);
        DeviceBase device = getDeviceInfo(param);
        report.setInstrumentName(device.getName());
        report.setInstrumentNo(device.getSn());
        report.setInstrumentNameAndNo(device.getName() + device.getSn());
        report.setReportName(device.getName() + report.getReportName());

        return report;
    }

    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();
        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());

        return reportContent;
    }

}

/**
 * GenAuditSpanReport
 * <p>生成人工零跨核查记录表</p>
 * {@code @description} 生成人工核查报表
 *
 * @version 1.0
 */
class GenAuditSpanReport extends ReportGenerator {

    public SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private EcatCore core;

    private IRealdataService realdataService;

    @Getter
    private AuditSpanReport report;

    public final static String FULL_SPAN = "500ppb";
    public final static String FULL_SPAN_CO = "50ppm";

    /**
     * 实际执行此方法 生成报告
     * @param records
     */
    public GenAuditSpanReport(EcatCore core, List<EnvQualityControlRecords> records) {
        super(core);
        this.core = core;
        report = new AuditSpanReport();
        report = parseRecordToReport(records);
        report.setComponent(ReportTypeEnum.AUDIT_SPAN.getComponent());

        // 用于报告展示的主要内容
        Map<String, Object> reportData = constructReportContent();
        report.setReportData(reportData);
        String reportContent = JsonUtils.toJsonString(reportData);
        report.setReportContent(reportContent);
    }

    /**
     * 获取关键参数
     * 查询实时数据表，获取某段时间内的数据
     * @example
     * <pre>
     *  keyParameters.add(new HashMap<String, Object>() {{
     *      put("tName", "Flow");
     *      put("tValue", "1.2L/min");
     *      put("tRange", "0~100L/min");
     *      put("tRemark", "处理记录111");
     *  }});
     *  keyParameters.add(new HashMap<String, Object>() {{
     *      put("tName", "GasPressure");
     *      put("tValue", "50.0psi");
     *      put("tRange", "-50~50.0psi");
     *      put("tRemark", "处理记录222");
     *  }});
     * @param beginPickTime 开始时间
     * @param  endPickTime 结束时间
     */
    private List< Map<String, Object> > queryKeyParameters(Date beginPickTime, Date endPickTime, String param, String deviceId) {
        // xxx监测仪 | esa-co | esa-no2 | esa-o3 | esa-so2 |
        List<String> types = new ArrayList<>();
        types.add("Flow");  // 流量
        types.add("SampleP");  // 采样压力
        types.add("GasPressure");  // 气体压力
        types.add("GasT");  // 气体温度
        types.add("InternalTemp");  // 内部温度
        types.add(param + "_concentration");  // XX浓度

        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("beginPickTime", beginPickTime);
        queryParams.put("endPickTime", endPickTime);
        queryParams.put("pids", types);
        queryParams.put("sid", deviceId);

        // 实时数据获取关键参数，每种参数取一条
        if (mry == null) {
            mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry().getIntegration("integration-ecat-core-ruoyi");
        }
        realdataService = mry.getSpringBean(IRealdataService.class);
        List< Map<String, Object> > keyDatas = realdataService.selectDistinctTypeData(queryParams);

        List< Map<String, Object> > keyParameters = new ArrayList<>();
        for (Map<String, Object> keyData : keyDatas) {
            keyParameters.add(new HashMap<String, Object>() {{
                put("tName", keyData.getOrDefault("pn", ""));
                put("tValue", keyData.getOrDefault("value", "")+ " " +keyData.getOrDefault("unit_name", ""));
                put("tRange", keyData.getOrDefault("range", ""));
                put("tRemark", keyData.getOrDefault("remark", ""));
            }});
        }
        return keyParameters;
    }

    /**
     * 解析质控记录中的用于报告的数据
     * @param records 质控记录
     * @return report 质控报告
     */
    private AuditSpanReport parseRecordToReport(List<EnvQualityControlRecords> records) {
        EnvQualityControlRecords records_0 = records.get(0);

        report.setReportDate(records_0.getCreateTime()); // 报告日期 默认是质控记录开始时间
        report.setFiler(records_0.getCreatedBy());  // 填表人 默认是质控记录创建者
        report.setReviewer(records_0.getUpdateBy());  // 复核人 默认是质控记录更新者
        report.setCreatedBy(records_0.getCreatedBy());
        report.setUpdatedBy(records_0.getUpdateBy());
        report.setGasType(records_0.getParameter());
        String param = ParameterEnum.getNameByCode(report.getGasType());
        report.setGasSource(param);  // 标气来源 暂为标气展示类型
        DeviceBase device = getDeviceInfo(param);
        report.setInstrumentName(device.getName());
        report.setReportName(device.getName() + report.getReportName());
        report.setInstrumentNo(device.getSn());
        report.setInstrumentNameAndNo(device.getName() + device.getSn());
        // 获取钢瓶气浓度
        String gasConcentration = getStdGasConcentration(param);
        report.setGasConcentration(gasConcentration == null ? "" : gasConcentration);

        String reportNote = "";
        // TODO: 聚合多个记录的结果到一张报告，目前只记录了最后一条记录的结果
        for (EnvQualityControlRecords record : records) {
            reportNote += record.getExecutionLog() + "\n";
            if (record.getQualityControlType().equals(AUDIT_SPAN_CHECK.getCode())) {
                // 从record中获取零点数据
                report.setSpanStartTime(sdf.format(record.getStartTime()));
                report.setSpanEndTime(sdf.format(record.getEndTime()));
                // 解析record.getExecutionLog(),将JsonString转换成Map
                Map<String, Object> executionLogMap = JsonUtils.parseMap(record.getExecutionLog(), String.class, Object.class);
                // {
                //   "result":[{"checkTime":"2025-08-05 05:23:46","checkData":41945.035},{"checkTime":"2025-08-05 05:24:46","checkData":41951.164},{"checkTime":"2025-08-05 05:25:46","checkData":41944.08},{"checkTime":"2025-08-05 05:26:46","checkData":41932.953},{"checkTime":"2025-08-05 05:27:46","checkData":41917.2}],
                //   "params":{"qualityControlType":"audit_span_check","taskType":"0","stdGasInPortName":"测量","taskDescription":"人工核查任务","gas":"CO","readDataSpan":60,"genGasConc":40.0,"taskName":"EnvQualityControlCustomTask","triggerType":"0","genGasTime":1362,"readDataCount":5}
                // }
                List<Map<String, String>> resultList = (List<Map<String, String>>) executionLogMap.get("result");
                Map<String, Object> params = (Map<String, Object>) executionLogMap.get("params");
                Double genGasConc = (Double) params.get("genGasConc");
                // 将checkConcentration单位由ppm转为ppb及扩大1000倍
                String checkConcentration = String.valueOf(genGasConc * 1000);
                report.setSpanStandardConcentration(gasConcentration);
                report.setCheckConcentration(checkConcentration);
                report.setSpanDisplayResponses(resultList);
                report.setSpanCalibrationResponse(checkConcentration);  // TODO 暂时为核查浓度值，实际取稳定6分钟，其中的一个值)。
            } else {
                throw new RuntimeException("Invalid report type for AuditSpanReport: " + record.getQualityControlType());
            }
        }
        report.setReportNote(reportNote);   // 备注 默认是质控记录结果评价

        // 关键参数
        List< Map<String, Object> > keyParameters = queryKeyParameters(records_0.getStartTime(), records_0.getEndTime(), param, device.getId());
        report.setKeyParameters(keyParameters);

        return report;
    }
    /**
     * 组装报表数据
     * return reportContent 对应到数据库字段 report_content
     */
    public Map<String, Object> constructReportContent() {

        Map<String, Object> reportContent = new HashMap<>();

        // 标题
        reportContent.put("title", report.getReportName());
        reportContent.put("component", report.getComponent());
        reportContent.put("report_display_type", report.getReportDisplayType());
        reportContent.put("gas_type", report.getGasType());

        Map<String, String> instrument_info = new HashMap<>();
        // 设备名称及编号
        instrument_info.put("instrument_name_and_no", report.getInstrumentNameAndNo());
        instrument_info.put("instrument_name", report.getInstrumentName());
        instrument_info.put("instrument_no", report.getInstrumentNo());
        // 校准日期
        instrument_info.put("report_date", new SimpleDateFormat("yyyy-MM-dd").format(report.getReportDate()));
        // 标气来源及编号
        instrument_info.put("gas_source_and_no", report.getGasSourceAndNo());
        instrument_info.put("gas_source", report.getGasSource());
        instrument_info.put("gas_no", report.getGasNo());
        // 标气浓度
        instrument_info.put("gas_concentration", report.getGasConcentration());
        reportContent.put("instrument_info", instrument_info);
        reportContent.put("start_time", report.getSpanStartTime());
        reportContent.put("end_time", report.getSpanEndTime());
        // 跨度结果
        List<Map<String, Object>> calibrationPoints = new ArrayList<>();
        // 遍历
        for (Map<String, String> auditCheckResultItem : report.getSpanDisplayResponses()) {
            calibrationPoints.add(
                new HashMap<String, Object>() {{
                    put("point_name", "自定义标点");
                    put("check_concentration", report.getCheckConcentration());
                    put("check_time", auditCheckResultItem.get("checkTime"));
                    put("check_data", auditCheckResultItem.get("checkData"));
                    put("standard_concentration", report.getSpanStandardConcentration());
                }}
            );
        }
        reportContent.put("calibration_points", calibrationPoints);
        String fullSpan = report.getGasType().equals(CO.getCode())? FULL_SPAN_CO : FULL_SPAN;
        reportContent.put("full_span", fullSpan);
        reportContent.put("span_drift_result", report.getSpanDriftResult());
        reportContent.put("key_parameters", report.getKeyParameters());

        return reportContent;
    }
}
