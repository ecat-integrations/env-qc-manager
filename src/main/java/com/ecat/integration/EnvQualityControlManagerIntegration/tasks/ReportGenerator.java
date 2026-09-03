package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.AccuracyReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.AuditSpanReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ConversionReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.GenAccuracyReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.GenAuditSpanReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.GenConversionReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.GenMultiCheckReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.GenPrecisionReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.GenTransferAndTraceReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.GenZeroAndSpanReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.MultiCheckReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.PrecisionReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ZeroAndSpanReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.JsonUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ZeroSpanDayPairSelector;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QcCurrentUser;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.CylinderArchiveSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionLogHelper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.service.ISysUserService;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.ecat.integration.EnvQualityControlManagerIntegration.tasks.report.ReportFormatSupport.recordCreatorRef;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.ACCURACY_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.AUDIT_SPAN_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.CONVERSION_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.MULTI_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.PRECISION_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.SPAN_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.ZERO_CHECK;

/**
 * ReportGenerator：质控报告生成编排基类（G-STRUCT-3 拆分后的主类）。
 * <p>本类只保留三块职责：</p>
 * <ul>
 *     <li>编排：{@link #generate(Instant, Instant)} 按质控类型分派到 {@code tasks.report} 包下
 *     Gen* 报表子类（七类：零跨/多点/精密度/准确度/转换效率/量值传递/人工核查）</li>
 *     <li>模板方法：{@link #constructReportContent()} 为子类覆写钩子（基类不可直接生成内容）；
 *     {@link #attachReportData(QcmReport)} 上提了子类构造器共同的「组装→挂 reportData→序列化 reportContent」收尾三步</li>
 *     <li>共享实例能力：设备解析（含 AC-C8 参数级预取缓存）、标气逻辑设备读取、填表人/复核人展示名解析</li>
 * </ul>
 * <p>静态格式化工具见 {@code tasks.report.ReportFormatSupport}，报表实体见 {@code tasks.report.ReportAttribute}。</p>
 *
 * @author caohongbo
 */
public class ReportGenerator {

    /**
     * 与质控记录 {@code start_time} 归日一致，用于日报分桶与 {@code report_date}。
     * 与 QcmRecord.QUERY_WINDOW_ZONE / EnvQualityControlGenReportTask.ZONE 同值不合并：查询窗、调度窗、报表归日三个域各自独立演进。
     */
    public static final ZoneId QC_REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    private EcatCore core;
    protected EcatCoreRuoyiIntegration mry;

    /** {@code Gen*} 报表子类会写日志，需对子类可见（非 private）。 */
    public static final Logger logger = LoggerFactory.getLogger(ReportGenerator.class);

    private IQcmRecordService recordsService;

    public String reportType;

    @Getter
    public QcmReport report;

    /**
     * 参数级设备预取缓存（AC-C8）：generate() 执行前一次性解析全部涉及气体的物理设备，
     * 同一气体 N 条记录只查 1 次注册表（key=气体参数名；value 可为 null=该气体未解析到设备，同样缓存避免重复降级告警）。
     */
    private final Map<String, DeviceBase> devicePrefetch;

    public ReportGenerator() {
        this.devicePrefetch = null;
    }

    public ReportGenerator(EcatCore core) {
        this.core = core;
        this.devicePrefetch = null;
    }

    public ReportGenerator(EcatCore core, Map<String, DeviceBase> devicePrefetch) {
        this.core = core;
        this.devicePrefetch = devicePrefetch;
    }

    /**
     * 模板方法收尾（G-STRUCT-3 上提）：子类构造器共同的「构造内容 Map → 挂 reportData → JSON 序列化 reportContent」。
     */
    protected void attachReportData(QcmReport target) {
        Map<String, Object> reportData = constructReportContent();
        target.setReportData(reportData);
        target.setReportContent(JsonUtils.toJsonString(reportData));
    }

    protected String getStdGasConcentration(String gasType) {
        return LogicDeviceReportSupport.readStandardGasCylinderConcentration(core, gasType);
    }

    /**
     * 报表「标气浓度」（成对口径，2026-09-02 单位修复）：值必须随真实单位展示，禁止裸数字。
     * 回落序：① execution_log 快照对（stdGasConcentration + stdGasConcentrationUnit，成对落库起才有）
     * ② record 冻结对（gas_concentration + gas_concentration_unit，完成时冻结列）
     * ③ live 即时读（旧裸串链，无单位——再经 {@link CylinderArchiveSupport#readArchive} 补单位）。
     * O₃ 无钢瓶、不写快照，结果为空。旧数据单位键与冻结对皆缺时只显数值（如实，不猜单位）。
     */
    protected String resolveReportStdGasConcentration(String gasParamName, QcmRecord... records) {
        if (records != null) {
            for (QcmRecord r : records) {
                if (r == null) {
                    continue;
                }
                String snap = QualityControlExecutionLogHelper.readStdGasConcentrationSnapshot(r.getExecutionLog());
                String snapUnit = QualityControlExecutionLogHelper.readStdGasConcentrationUnitSnapshot(r.getExecutionLog());
                String frozenUnit = r.getGasConcentrationUnit() != null ? r.getGasConcentrationUnit().trim() : "";
                String frozenValue = r.getGasConcentration() != null
                        ? r.getGasConcentration().stripTrailingZeros().toPlainString() : "";
                if (snap != null && !snap.isEmpty()) {
                    if (!snapUnit.isEmpty()) {
                        return snap + " " + snapUnit;
                    }
                    // 单位键缺席（成对落库前的旧记录）→ 冻结对补单位（ResultSnapshotWriter 完成时冻结列，值同源）
                    if (!frozenUnit.isEmpty() && !frozenValue.isEmpty()) {
                        return frozenValue + " " + frozenUnit;
                    }
                    return snap;
                }
                // 无快照值：冻结对完整则直接用（值+单位同源成对，优于下面的 live 裸串）
                if (!frozenValue.isEmpty() && !frozenUnit.isEmpty()) {
                    return frozenValue + " " + frozenUnit;
                }
            }
        }
        if (gasParamName == null || gasParamName.trim().isEmpty()) {
            return "";
        }
        String live = getStdGasConcentration(gasParamName);
        if (live == null || live.isEmpty()) {
            return "";
        }
        // live 裸串无单位：经档案成对链补（与 ResultSnapshotWriter 冻结同源）
        CylinderArchiveSupport.GasTrace trace = CylinderArchiveSupport.readArchive(core, gasParamName);
        if (trace != null && trace.concentrationUnit != null && !trace.concentrationUnit.trim().isEmpty()) {
            return live + " " + trace.concentrationUnit.trim();
        }
        return live;
    }

    /**
     * 标气溯源（§4.2 第 3 步「消费同源」）：优先读 record 完成时冻结的 gas_source/gas_no 快照，
     * 冻结缺失项再回退标准气逻辑设备模糊扫描（历史记录未冻结时兜底）。
     */
    protected void applyStandardGasSourceAndNo(QcmReport target, String gasParameterName, QcmRecord frozenRecord) {
        if (target == null) {
            return;
        }
        if (frozenRecord != null) {
            if (notBlank(frozenRecord.getGasSource())) {
                target.setGasSource(frozenRecord.getGasSource());
            }
            if (notBlank(frozenRecord.getGasNo())) {
                target.setGasNo(frozenRecord.getGasNo());
            }
        }
        if (!notBlank(target.getGasSource()) || !notBlank(target.getGasNo())) {
            applyStandardGasSourceAndNoFromLogicDevices(target, gasParameterName);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * 从标准气逻辑设备补充 {@link QcmReport} 的标气来源/编号（读到非空则覆盖原值）。
     * 历史兜底通道：钢瓶气设备无来源/编号属性（§4.2 实证），常规路径见
     * {@link #applyStandardGasSourceAndNo(QcmReport, String, QcmRecord)}。
     */
    protected void applyStandardGasSourceAndNoFromLogicDevices(QcmReport target, String gasParameterName) {
        if (core == null || target == null || gasParameterName == null || gasParameterName.trim().isEmpty()) {
            return;
        }
        try {
            String[] pair = LogicDeviceReportSupport.tryReadStandardGasSourceAndNo(core, gasParameterName);
            if (pair == null) {
                return;
            }
            if (pair[0] != null && !pair[0].isEmpty()) {
                target.setGasSource(pair[0]);
            }
            if (pair[1] != null && !pair[1].isEmpty()) {
                target.setGasNo(pair[1]);
            }
        } catch (Exception e) {
            logger.debug("applyStandardGasSourceAndNoFromLogicDevices skipped: {}", e.getMessage());
        }
    }

    /**
     * 将 {@link QcmReport#getGasSource()} 与 {@link QcmReport#getGasNo()} 拼入各报表子类的
     * {@code gasSourceAndNo} 展示字段（若子类定义了该字段）。
     */
    protected void refreshReportGasSourceAndNoCombined(QcmReport r) {
        if (r == null) {
            return;
        }
        String g = r.getGasSource() != null ? r.getGasSource().trim() : "";
        String n = r.getGasNo() != null ? r.getGasNo().trim() : "";
        String combo = (g + " " + n).trim();
        if (r instanceof ZeroAndSpanReport) {
            ((ZeroAndSpanReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof MultiCheckReport) {
            ((MultiCheckReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof PrecisionReport) {
            ((PrecisionReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof AccuracyReport) {
            ((AccuracyReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof ConversionReport) {
            ((ConversionReport) r).setGasSourceAndNo(combo);
        } else if (r instanceof AuditSpanReport) {
            ((AuditSpanReport) r).setGasSourceAndNo(combo);
        }
    }

    /**
     * 关键参数：优先使用 execution_log 中已存快照；否则读当前逻辑设备属性。
     * {@code beginPickTime}/{@code endPickTime} 在存在 {@link QualityControlExecutionLogHelper#QC_PHASE_TIMELINES_KEY}
     * 读数相位时已解析为该窗口（供将来按时刻查询历史读数时沿用），当前实现仍与即时读数一致。
     */
    protected List<Map<String, Object>> queryKeyParameters(
            Instant beginPickTime, Instant endPickTime, String param, String deviceId) {
        return LogicDeviceReportSupport.buildAnalyzerKeyParameters(core, param);
    }

    /**
     * 根据气态参数获取物理 {@link DeviceBase}。
     * <p>优先从 {@link EcatCore#getDeviceRegistry()} 中标准分析仪逻辑设备的 {@code mappings} 解析
     * {@code device_id}；若无映射则按历史约定降级为 esa-* / sms-qc。</p>
     *
     * @param param 气态参数 (SO2, NO2, CO, O3)
     * @return 设备对象；core 未初始化或设备不存在时可能为 {@code null}
     */
    public DeviceBase getDeviceInfo(String param) {
        if (devicePrefetch != null && devicePrefetch.containsKey(param)) {
            return devicePrefetch.get(param);
        }
        return lookupDeviceInfo(param);
    }

    private DeviceBase lookupDeviceInfo(String param) {
        if (core == null) {
            logger.error("EcatCore 未初始化，无法解析设备");
            return null;
        }
        String deviceId = LogicDeviceReportSupport.resolveAnalyzerPhysicalDeviceId(core, param);
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = fallbackPhysicalDeviceIdForReportGas(param);
            logger.warn(
                    "逻辑设备未解析到物理 device_id，气体 {} 使用降级设备 {}；请检查 logicdevice.* 的 data.mappings 是否为主浓度属性配置了 device_id（无物理设备/仅占位时无法解析）。将 LogicDeviceReportSupport 调至 DEBUG 可查看 mappingsKeys 与逻辑入口是否注册",
                    param,
                    deviceId);
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
     * 逻辑层未就绪时的物理设备 ID（与历史默认表一致）。
     */
    private static String fallbackPhysicalDeviceIdForReportGas(String param) {
        if (param == null) {
            return "sms-qc";
        }
        switch (param.trim().toUpperCase(Locale.ROOT)) {
            case "SO2":
                return "esa-so2";
            case "NO2":
                return "esa-no2";
            case "O3":
                return "esa-o3";
            case "CO":
                return "esa-co";
            default:
                return "sms-qc";
        }
    }

    protected void ensureRuoyiIntegration() {
        if (mry == null && core != null) {
            try {
                mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry().getIntegration("integration-ecat-core-ruoyi");
            } catch (Exception e) {
                // 可选依赖（ruoyi 集成/bean）不可用时降级，调试级留痕（RED-3：不吞 Error）
                logger.debug("optional dependency lookup degraded", e);
            }
        }
    }

    private ISysUserService tryResolveUserService() {
        ensureRuoyiIntegration();
        if (mry != null) {
            try {
                ISysUserService svc = mry.getSpringBean(ISysUserService.class);
                if (svc != null) {
                    return svc;
                }
            } catch (Exception e) {
                // 可选依赖（ruoyi 集成/bean）不可用时降级，调试级留痕（RED-3：不吞 Error）
                logger.debug("optional dependency lookup degraded", e);
            }
        }
        try {
            return SpringUtils.getBean(ISysUserService.class);
        } catch (Exception e) {
                logger.debug("optional lookup degraded", e);
            return null;
        }
    }

    private static boolean looksLikeUnsignedLongUserId(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String displayLabelFromSysUser(SysUser u) {
        if (u == null) {
            return "";
        }
        String nick = u.getNickName();
        if (nick != null && !nick.trim().isEmpty()) {
            return nick.trim();
        }
        String un = u.getUserName();
        return un == null ? "" : un.trim();
    }

    private String displayNameFromLoginUserOnly() {
        try {
            LoginUser lu = SecurityUtils.getLoginUser();
            if (lu == null || lu.getUser() == null) {
                return "";
            }
            String nick = lu.getUser().getNickName();
            if (nick != null && !nick.trim().isEmpty()) {
                return nick.trim();
            }
        } catch (Exception e) {
                logger.debug("optional lookup degraded", e);
            // 无 Security 上下文
        }
        return "";
    }

    /**
     * 报表填表人/复核人展示名：按 Ruoyi 用户表解析「用户名称」（{@code nick_name}）；与当前登录用户一致时再走 Security。
     * 优先通过 {@link EcatCoreRuoyiIntegration} 取 {@link ISysUserService}，以兼容部分线程中 {@link SpringUtils} 不可用的情况。
     */
    protected String resolveReportPersonDisplayName(String storedUserRef) {
        if (storedUserRef == null || storedUserRef.trim().isEmpty()) {
            return "";
        }
        String key = storedUserRef.trim();
        ISysUserService userService = tryResolveUserService();
        if (userService != null) {
            try {
                SysUser u = userService.selectUserByUserName(key);
                if (u == null && looksLikeUnsignedLongUserId(key)) {
                    u = userService.selectUserById(Long.parseLong(key));
                }
                if (u != null) {
                    String label = displayLabelFromSysUser(u);
                    if (!label.isEmpty()) {
                        return label;
                    }
                }
            } catch (Exception e) {
                // 可选依赖（ruoyi 集成/bean）不可用时降级，调试级留痕（RED-3：不吞 Error）
                logger.debug("optional dependency lookup degraded", e);
            }
        }
        try {
            String u = SecurityUtils.getUsername();
            if (u != null && !u.trim().isEmpty() && u.trim().equalsIgnoreCase(key) && !"anonymousUser".equalsIgnoreCase(u.trim())) {
                String fromLogin = displayNameFromLoginUserOnly();
                if (!fromLogin.isEmpty()) {
                    return fromLogin;
                }
                return u.trim();
            }
        } catch (Exception e) {
                logger.debug("optional lookup degraded", e);
            // 无 Security 上下文
        }
        return key;
    }

    /** 当前登录用户的 RuoYi 用户名；无 Security 上下文时为空。 */
    protected String currentLoginUsername() {
        return QcCurrentUser.usernameOrEmpty();
    }

    /**
     * 报告填表人：优先当前登录用户名；无登录时回退质控记录创建人。复核人默认空，不由填表人/更新人顶上。
     */
    protected void applyReportFilerAndEmptyReviewer(QcmReport target, QcmRecord... records) {
        if (target == null) {
            return;
        }
        String login = currentLoginUsername();
        String fromRecord = "";
        if (records != null) {
            for (QcmRecord r : records) {
                fromRecord = recordCreatorRef(r);
                if (!fromRecord.isEmpty()) {
                    break;
                }
            }
        }
        String filer = !login.isEmpty() ? login : fromRecord;
        target.setFiler(filer);
        target.setReviewer("");
        target.setCreatedBy(filer);
        target.setUpdatedBy("");
    }

    /**
     * 报告「填表人」：优先当前登录用户名，否则用记录创建人引用。
     */
    protected String resolveReportFilerDisplayName(String recordCreatorRef) {
        String login = currentLoginUsername();
        if (!login.isEmpty()) {
            return login;
        }
        return recordCreatorRef == null ? "" : recordCreatorRef.trim();
    }

    /**
     * 组装报告数据（模板方法钩子）：由 Gen* 子类覆写；基类 ReportGenerator 本身不产出报表内容。
     */
    public Map<String, Object> constructReportContent() {
        throw new UnsupportedOperationException();
    }

    /**
     * 生成某段时间的报告
     * @param startTime 开始时间
     * @param endTime  结束时间
     */
    public List<QcmReport> generate(Instant startTime, Instant endTime) {
        // 查询质控记录表，获取某段时间内的质控记录，遍历成功完成的记录，存入质控报告表，注意零跨的特殊处理
        if (mry == null) {
            mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry().getIntegration("integration-ecat-core-ruoyi");
        }
        recordsService = mry.getSpringBean(IQcmRecordService.class);
        List<QcmRecord> recordResults = recordsService.selectQcmRecordByTypeTime(startTime, endTime, null,
                ExecutionStatusEnum.SUCCESS.getCode().intValue());
        logger.info("ReportGenerator.generate.selectQcmRecordByTypeTime(" + startTime + "," + endTime + ")"
                + "recordResults -> " + recordResults);

        List<QcmReport> reports = new ArrayList<>();
        if (recordResults.isEmpty()) {
            return reports;
        }

        // AC-C8 设备预取：先汇总全部涉及气体参数名，逐气体解析一次设备（N+1 → 参数数）
        Map<String, DeviceBase> devicePrefetch = new HashMap<>();
        for (QcmRecord recordResult : recordResults) {
            String name = ParameterEnum.getNameByCode(recordResult.getParameter());
            if (name != null && !devicePrefetch.containsKey(name)) {
                devicePrefetch.put(name, this.lookupDeviceInfo(name));
            }
        }

        // 零跨时间线
        Map<String, List<QcmRecord>> zeroSpanLineMap = newLineMapByGas();

        // 人工核查时间线
        Map<String, List<QcmRecord>> auditSpanLineMap = newLineMapByGas();

        // 遍历质控记录列表
        for (QcmRecord recordResult : recordResults) {
            try {
                // 未成功的质控记录不生成质控报告，而在质控记录里溯查原因，并记录在质控记录表里
                if (recordResult.getQualityControlType().equals(ZERO_CHECK.getCode())
                        || recordResult.getQualityControlType().equals(SPAN_CHECK.getCode())) {
                    // 分参数 放入零跨时间线，等待滑动时间窗口处理零跨聚合
                    String param = recordResult.getParameter();
                    if (zeroSpanLineMap.containsKey(param)) {
                        zeroSpanLineMap.get(param).add(recordResult);
                    }

                } else if (recordResult.getQualityControlType().equals(MULTI_CHECK.getCode())) {
                    reports.add(new GenMultiCheckReport(core, recordResult, devicePrefetch).getReport());

                } else if (recordResult.getQualityControlType().equals(PRECISION_CHECK.getCode())) {
                    reports.add(new GenPrecisionReport(core, recordResult, devicePrefetch).getReport());

                } else if (recordResult.getQualityControlType().equals(ACCURACY_CHECK.getCode())) {
                    reports.add(new GenAccuracyReport(core, recordResult, devicePrefetch).getReport());

                } else if (recordResult.getQualityControlType().equals(CONVERSION_CHECK.getCode())) {
                    reports.add(new GenConversionReport(core, recordResult, devicePrefetch).getReport());

                } else if (recordResult.getQualityControlType().equals(QualityControlTypeEnum.LEGACY_CALIBRATION_CHECK_TYPE)) {
                    reports.add(new GenTransferAndTraceReport(core, recordResult, devicePrefetch).getReport());

                } else if (recordResult.getQualityControlType().equals(AUDIT_SPAN_CHECK.getCode())) {
                    // 目的是将当天的所有人工核查聚合到一张报告中
                    String param = recordResult.getParameter();
                    if (auditSpanLineMap.containsKey(param)) {
                        auditSpanLineMap.get(param).add(recordResult);
                    }

                } else {
                    throw new UnsupportedOperationException("暂不支持该类型质控");
                }
            } catch (UnsupportedOperationException e) {
                logger.warn("Generate report failed for record: {}, reason: {}", recordResult, e.getMessage());
            }
        }

        // 遍历 zeroSpanLineMap：按参数、按自然日（start_time 在 QC_REPORT_ZONE）分桶后择优配对，每日至多一张零跨报告
        for (Map.Entry<String, List<QcmRecord>> entry : zeroSpanLineMap.entrySet()) {
            List<QcmRecord> zeroSpanLine = entry.getValue();
            if (zeroSpanLine.isEmpty()) {
                continue;
            }
            zeroSpanLine.sort(Comparator.comparing(QcmRecord::getStartTime, Comparator.nullsFirst(Comparator.naturalOrder())));
            Map<LocalDate, List<QcmRecord>> byDay = new TreeMap<>();
            for (QcmRecord r : zeroSpanLine) {
                if (r.getStartTime() == null) {
                    continue;
                }
                LocalDate day = r.getStartTime().atZone(QC_REPORT_ZONE).toLocalDate();
                byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(r);
            }
            for (Map.Entry<LocalDate, List<QcmRecord>> dayEntry : byDay.entrySet()) {
                List<QcmRecord> chosen = ZeroSpanDayPairSelector.select(dayEntry.getValue());
                if (chosen.isEmpty()) {
                    continue;
                }
                reports.add(new GenZeroAndSpanReport(core, dayEntry.getKey(), chosen, devicePrefetch).getReport());
            }
        }

        // 遍历auditSpanLineMap的各个参数，处理人工核查时间线上的所有跨度质控
        for (Map.Entry<String, List<QcmRecord>> entry : auditSpanLineMap.entrySet()) {
            List<QcmRecord> auditSpanLine = entry.getValue();
            if (!auditSpanLine.isEmpty()) {
                reports.add(new GenAuditSpanReport(core, auditSpanLine, devicePrefetch).getReport());
            }
        }

        return reports;
    }

    private static Map<String, List<QcmRecord>> newLineMapByGas() {
        Map<String, List<QcmRecord>> m = new HashMap<>();
        m.put(ParameterEnum.SO2.getCode(), new ArrayList<>());
        m.put(ParameterEnum.NO2.getCode(), new ArrayList<>());
        m.put(ParameterEnum.O3.getCode(), new ArrayList<>());
        m.put(ParameterEnum.CO.getCode(), new ArrayList<>());
        return m;
    }

    /**
     * 单条成功质控记录的报告预览数据（与定时任务 Gen* 报表生成器同源），供前端「质控结果」弹窗复用。
     */
    public static Map<String, Object> buildSingleRecordPreviewPayload(EcatCore core, QcmRecord r) {
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(r, "record");
        if (r.getStartTime() == null) {
            throw new IllegalArgumentException("record.startTime is required");
        }
        LocalDate day = r.getStartTime().atZone(QC_REPORT_ZONE).toLocalDate();
        String qcType = r.getQualityControlType();
        Object gen;
        if (ZERO_CHECK.getCode().equals(qcType) || SPAN_CHECK.getCode().equals(qcType)) {
            gen = new GenZeroAndSpanReport(core, day, Collections.singletonList(r));
        } else if (MULTI_CHECK.getCode().equals(qcType)) {
            gen = new GenMultiCheckReport(core, r);
        } else if (PRECISION_CHECK.getCode().equals(qcType)) {
            gen = new GenPrecisionReport(core, r);
        } else if (ACCURACY_CHECK.getCode().equals(qcType)) {
            gen = new GenAccuracyReport(core, r);
        } else if (CONVERSION_CHECK.getCode().equals(qcType)) {
            gen = new GenConversionReport(core, r);
        } else if (QualityControlTypeEnum.LEGACY_CALIBRATION_CHECK_TYPE.equals(qcType)) {
            gen = new GenTransferAndTraceReport(core, r);
        } else if (AUDIT_SPAN_CHECK.getCode().equals(qcType)) {
            gen = new GenAuditSpanReport(core, Collections.singletonList(r));
        } else {
            throw new IllegalArgumentException("Unsupported quality control type for preview: " + qcType);
        }
        try {
            Object reportBean = gen.getClass().getMethod("getReport").invoke(gen);
            String component = (String) reportBean.getClass().getMethod("getComponent").invoke(reportBean);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) reportBean.getClass().getMethod("getReportData").invoke(reportBean);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("component", component != null ? component : "");
            out.put("reportData", data != null ? data : new HashMap<String, Object>());
            if ("ReportD4".equals(component) && data != null && !data.containsKey("audit_result")) {
                try {
                    Object cal = reportBean.getClass().getMethod("getCalibrationResult").invoke(reportBean);
                    if (cal != null) {
                        data.put("audit_result", String.valueOf(cal));
                    }
                } catch (ReflectiveOperationException e) {
                    logger.debug("reflective attribute read degraded", e);
                }
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to assemble report preview", e);
        }
    }

}
