package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.api.ResultFilter;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkBatchState;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkExecutionResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkExecutionStatus;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkFailureReason;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkInstrument;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkPlanSetting;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkPlanStatus;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkQcType;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkRecordDetail;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkScheduleType;
import com.ecat.integration.EnvQualityControlManagerIntegration.api.SdkTriggerSource;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordKeyParamMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPhaseMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPointMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.impl.QcmRecordServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SDK 读面真库符合性集成测试（手动门控）：{@link QualityControlSdkImpl} 的读方法对部署云库
 * 真实数据端到端跑通，锁「枚举翻译层在真数据上零异常且值正确」。mock 单测证明的是装配正确性，
 * 证明不了存储面词汇与 api 闭域一致——存储面出现未知词汇（新仪器码/新质控类型/新失败原因）会在
 * 出口翻译处抛 IAE（R4 纪律），这类生产风险只有真库数据能探到。
 *
 * <p>E2E opt-in 门控遵循 core-unittest skill 统一标记（同 E2ELifecycleTest 惯例）：依赖外部部署库，
 * 默认 mvnd test 整类 Skipped；显式 -Decat.e2e=true 才跑。运行方式：
 * <pre>mvnd test -Decat.e2e=true -Dtest=QcmSdkCloudDbConformanceE2ETest</pre>
 * 连接凭据不落源码（安全红线），三层解析：-Dqcm.it.clouddb.{url,user,password} 覆盖 >
 * 环境变量 POSTGRES_{HOST,PORT,DB,USER,PASSWORD} > workspace 根 .env 的 POSTGRES_* 键
 * （与 core-integration-test skill 同源）。全缺则带指引失败。门控开着但连不上库 = 环境问题，显式失败不静默跳过。</p>
 *
 * <p>只读零写：全部走 SELECT——trigger/stop 写面由编排器层浏览器回归覆盖，SDK 装配与拒绝路径由
 * {@link QualityControlSdkImplTest} 单测锁。装配缝取最小：生产 mapper XML + PG 真库 = 真 mapper
 * （与运行时同一套 XML 解析路径）；记录服务用生产实现（透传真 mapper）；编排器 mock
 * （queryRunning 空闲态即 runningBatchId 为 null）；计划服务 mock 委托真 QcmPlanMapper
 * （生产 selectList 只多一层 scheduleSummary 展示装饰，SDK 不消费该字段）。</p>
 *
 * <p>期望值锚点为 2026-09-09 部署库侦察结果：qcm_record#119（parameter='1'→SO2、
 * quality_control_type='1'→SPAN_CHECK、task_type='1'→MANUAL、execution_status=3→FAILED、
 * failure_reason 空）、qcm_plan#1/#4、EXECUTOR_BUSY_CONFLICT 留痕行 11 条。锚点行被清理时
 * 测试如实失败并提示重新侦察——暴露的是「部署库与期望值漂移」，不得放宽断言或伪造期望。</p>
 *
 * @author coffee
 */
@EnabledIfSystemProperty(named = "ecat.e2e", matches = "true")
class QcmSdkCloudDbConformanceE2ETest {

    /** 侦察锚点记录（手动终止的 FAILED 行，词汇域覆盖 SO2/SPAN_CHECK/MANUAL/FAILED/null 失败原因）。 */
    private static final long RECON_RECORD_ID = 119L;
    private static final String RECON_RECORD_BATCH_ID = "bcebe068-9dc0-4ab1-9081-e63bb0e58173";
    private static final String RECON_RECORD_TRIGGER_USER = "Admin7s9k2G5";
    private static final String RECON_RECORD_RESULT_EVALUATION = "流程被 Admin7s9k2G5 手动终止";

    /** 侦察时 qcm_record 内 SO2+span_check 行与其中 EXECUTOR_BUSY_CONFLICT 留痕行各不少于 11（库只增不减的保守下限）。 */
    private static final int RECON_SO2_SPAN_ROW_FLOOR = 11;
    private static final int RECON_BUSY_ROW_FLOOR = 11;

    /** 侦察锚点计划：#1 多仪器零点（4 仪器/PAUSED）、#4 跨度核查-SO2（ACTIVE）。 */
    private static final long RECON_MULTI_ZERO_PLAN_ID = 1L;
    private static final long RECON_ACTIVE_SPAN_PLAN_ID = 4L;

    /** 凭据不落源码（安全红线）：三层解析——系统属性 > 环境变量 > workspace 根 .env（POSTGRES_* 键）。
     *  任一层给全 host/user/password 即可用；全缺则带指引失败，不猜测不兜底。 */
    private static String DB_URL;
    private static String DB_USER;
    private static String DB_PASSWORD;

    /** 在 @EnabledIfSystemProperty 门控之后、bootSdkOverCloudDb 首行显式调用（默认跳过时不触碰任何
     *  外部资源；JUnit5 同级多 @BeforeAll 顺序非确定，显式调用收死先后）。 */
    private static void resolveDbCredentials() {
        String url = System.getProperty("qcm.it.clouddb.url");
        String user = System.getProperty("qcm.it.clouddb.user");
        String password = System.getProperty("qcm.it.clouddb.password");
        Map<String, String> env = parseWorkspaceEnvFile();
        String host = firstNonBlank(System.getenv("POSTGRES_HOST"), env.get("POSTGRES_HOST"));
        String port = firstNonBlank(System.getenv("POSTGRES_PORT"), env.get("POSTGRES_PORT"));
        String db = firstNonBlank(System.getenv("POSTGRES_DB"), env.get("POSTGRES_DB"));
        user = firstNonBlank(user, System.getenv("POSTGRES_USER"), env.get("POSTGRES_USER"));
        password = firstNonBlank(password, System.getenv("POSTGRES_PASSWORD"), env.get("POSTGRES_PASSWORD"));
        if (url == null || url.trim().isEmpty()) {
            if (host != null && !host.trim().isEmpty()) {
                url = "jdbc:postgresql://" + host.trim() + ":" + (port == null ? "5432" : port.trim())
                        + "/" + (db == null ? "ecat" : db.trim());
            }
        }
        if (url == null || url.trim().isEmpty() || user == null || user.trim().isEmpty()
                || password == null || password.trim().isEmpty()) {
            throw new IllegalStateException("真库 E2E 缺数据库凭据（源码不落敏感信息）。提供方式任选其一："
                    + "① -Dqcm.it.clouddb.url/-Dqcm.it.clouddb.user/-Dqcm.it.clouddb.password；"
                    + "② 环境变量 POSTGRES_HOST/PORT/DB/USER/PASSWORD；"
                    + "③ workspace 根 .env 的 POSTGRES_* 键（与 core-integration-test skill 同源）。");
        }
        DB_URL = url.trim();
        DB_USER = user.trim();
        DB_PASSWORD = password.trim();
    }

    /** workspace 根 .env（模块目录上两级）KEY=VALUE 平面解析；文件不存在返回空表（环境变量层兜底前）。 */
    private static Map<String, String> parseWorkspaceEnvFile() {
        Map<String, String> parsed = new HashMap<>();
        Path envFile = Paths.get("..", "..", ".env");
        if (!Files.isReadable(envFile)) {
            return parsed;
        }
        try {
            for (String line : Files.readAllLines(envFile)) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) {
                    continue;
                }
                parsed.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 workspace .env 失败：" + envFile.toAbsolutePath(), e);
        }
        return parsed;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c;
            }
        }
        return null;
    }

    /** 生产 mapper XML 资源（与运行时 mybatis 加载同一批文件）。 */
    private static final String[] MAPPER_RESOURCES = {
            "mapper/quality_control/QcmRecordMapper.xml",
            "mapper/quality_control/QcmPlanMapper.xml",
            "mapper/quality_control/QcmRecordPhaseMapper.xml",
            "mapper/quality_control/QcmRecordKeyParamMapper.xml",
            "mapper/quality_control/QcmRecordPointMapper.xml",
    };

    private static final BoundSqlCaptor sqlCaptor = new BoundSqlCaptor();
    private static PooledDataSource dataSource;
    private static SqlSession sqlSession;
    private static QcmRecordMapper recordMapper;
    private static QcmPlanMapper planMapper;
    private static QualityControlSdkImpl sdk;

    @BeforeAll
    static void bootSdkOverCloudDb() {
        resolveDbCredentials();
        dataSource = new PooledDataSource("org.postgresql.Driver", DB_URL, DB_USER, DB_PASSWORD);
        dataSource.setDefaultAutoCommit(true);
        try (java.sql.Connection ignored = dataSource.getConnection()) {
            // 只探连通性；取出的连接归还池，后续语句复用
        } catch (Exception e) {
            throw new IllegalStateException("部署云库不可达（qcm.it.clouddb=true 已开，属环境问题非代码缺陷）: "
                    + DB_URL + " → " + e.getMessage(), e);
        }
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("qcm-it-clouddb", new JdbcTransactionFactory(), dataSource));
        configuration.addInterceptor(sqlCaptor);
        parseProductionMapperXml(configuration);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = factory.openSession(true);
        recordMapper = sqlSession.getMapper(QcmRecordMapper.class);
        planMapper = sqlSession.getMapper(QcmPlanMapper.class);
        QcmRecordPhaseMapper phaseMapper = sqlSession.getMapper(QcmRecordPhaseMapper.class);
        QcmRecordKeyParamMapper keyParamMapper = sqlSession.getMapper(QcmRecordKeyParamMapper.class);
        QcmRecordPointMapper pointMapper = sqlSession.getMapper(QcmRecordPointMapper.class);

        QcmExecutionOrchestrator orchestrator = mock(QcmExecutionOrchestrator.class);
        when(orchestrator.runningBatchId()).thenReturn(null);
        IQcmRecordService recordService = new QcmRecordServiceImpl(recordMapper, orchestrator);
        IQcmPlanService planService = mock(IQcmPlanService.class);
        when(planService.selectList(any())).thenAnswer(
                invocation -> planMapper.selectList(invocation.getArgument(0, QcmPlan.class)));

        sdk = new QualityControlSdkImpl(orchestrator, recordService, recordMapper, new PlanParamValidator(),
                phaseMapper, keyParamMapper, pointMapper, planService);
    }

    @AfterAll
    static void closeSessionAndPool() {
        if (sqlSession != null) {
            sqlSession.close();
        }
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    /** 生产 mapper XML 原文进同一 Configuration（namespace 自动绑接口，与运行时解析同路径）。 */
    private static void parseProductionMapperXml(Configuration configuration) {
        for (String resource : MAPPER_RESOURCES) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            } catch (Exception e) {
                throw new IllegalStateException("生产 mapper XML 解析失败: " + resource + " → " + e.getMessage(), e);
            }
        }
    }

    @Test
    void queryExecution_translatesRealBatchToApiEnums() {
        QcmRecord anchor = reconRecord();

        SdkBatchState state = sdk.queryExecution(anchor.getBatchId());
        assertEquals(anchor.getBatchId(), state.getBatchId());
        assertTrue(state.isTerminal(), "锚点批次为手动终止的 FAILED 终态行");
        assertEquals(1, state.getRecords().size());

        SdkBatchState.RecordSummary summary = state.getRecords().get(0);
        assertEquals(RECON_RECORD_ID, summary.getRecordId());
        // 库 parameter='1' → 出参必须是 api 仪器枚举，数字码不外泄（R1/R4 翻译方向）
        assertEquals(SdkInstrument.SO2, summary.getInstrument());
        assertEquals(3, summary.getStatus());
        assertEquals(SdkExecutionStatus.FAILED, summary.getStatusName());
        assertNotNull(summary.getStartTime());
        assertNotNull(summary.getEndTime());
        assertEquals(RECON_RECORD_RESULT_EVALUATION, summary.getResultEvaluation());
    }

    @Test
    void queryResults_instrumentAndQcTypeFilter_sentToSqlAsStorageCodes() {
        sqlCaptor.clear();
        ResultFilter filter = ResultFilter.builder()
                .instrument(SdkInstrument.SO2)
                .qcType(SdkQcType.SPAN_CHECK)
                .build();

        List<SdkExecutionResult> results = sdk.queryResults(filter);

        // SQL 参数对账（翻译方向）：api 枚举进 mapper 前必须换算为存储词汇（旧契约「传字母查空」陷阱在边界消除）
        BoundSqlCaptor.CapturedQuery query = sqlCaptor.byStatement(QcmRecordMapper.class.getName() + ".selectByFilter");
        assertEquals("1", query.params.get("instrument"), "SQL 收到的仪器参数应是存储码 1");
        // 质控类型过滤与仪器同向：selectByFilter 落 qcm_record.quality_control_type 列，该列落数字码
        // （R7 双路径：记录侧=码、计划侧=name），故下 SQL 的必须是码，计划侧 name 恒查空
        assertEquals("1", query.params.get("qcType"), "SQL 收到的质控类型应是记录侧存储码");
        assertNull(query.params.get("triggerSource"), "未给的过滤不下 SQL");
        assertNull(query.params.get("begin"), "未给的过滤不下 SQL");
        assertEquals(QualityControlSdkImpl.QUERY_RESULT_LIMIT + 1, query.params.get("limit"), "超限探测取上限+1");
        assertTrue(query.sql.contains("parameter = ?"), "仪器过滤落 parameter 列");
        assertTrue(query.sql.contains("quality_control_type = ?"), "质控类型过滤落 quality_control_type 列");

        assertTrue(results.size() >= RECON_SO2_SPAN_ROW_FLOOR,
                "SO2+跨度核查命中行数低于侦察下限，部署库可能被清理（实得 " + results.size() + "）");
        int busyRows = 0;
        for (SdkExecutionResult result : results) {
            assertEquals(SdkInstrument.SO2, result.getInstrument());
            assertEquals(SdkQcType.SPAN_CHECK, result.getQcType());
            assertNotNull(result.getExecutionStatusName(), "执行状态须译成 api 枚举");
            if (result.getFailureReason() != null) {
                // 全库唯一结构化失败原因词汇：出现其它值即 SdkFailureReason 闭域落后于落库词汇（生产风险）
                assertEquals(SdkFailureReason.EXECUTOR_BUSY_CONFLICT, result.getFailureReason());
                busyRows++;
            }
        }
        assertTrue(busyRows >= RECON_BUSY_ROW_FLOOR,
                "EXECUTOR_BUSY_CONFLICT 留痕行数低于侦察下限（实得 " + busyRows + "）");
    }

    @Test
    void queryResults_triggerSourceFilter_sentToSqlAsTaskTypeCode() {
        assertTriggerSourceFilter(SdkTriggerSource.SCHEDULED, "0");
        assertTriggerSourceFilter(SdkTriggerSource.MANUAL, "1");
    }

    private void assertTriggerSourceFilter(SdkTriggerSource source, String expectedTaskTypeCode) {
        sqlCaptor.clear();
        List<SdkExecutionResult> results = sdk.queryResults(
                ResultFilter.builder().triggerSource(source).build());

        BoundSqlCaptor.CapturedQuery query = sqlCaptor.byStatement(QcmRecordMapper.class.getName() + ".selectByFilter");
        assertEquals(expectedTaskTypeCode, query.params.get("triggerSource"),
                "触发源过滤下 SQL 的应是 task_type 编码");
        assertFalse(results.isEmpty(), source + " 源在部署库应有历史行");
        for (SdkExecutionResult result : results) {
            assertEquals(source, result.getTriggerSource(), "往返后触发源词汇不得漂移");
        }
    }

    @Test
    void getRecordDetail_translatesRealRecordToApiEnums() {
        SdkRecordDetail detail = sdk.getRecordDetail(RECON_RECORD_ID);

        assertEquals(RECON_RECORD_ID, detail.getRecordId());
        assertEquals(RECON_RECORD_BATCH_ID, detail.getBatchId());
        assertEquals(SdkQcType.SPAN_CHECK, detail.getQcType());
        assertEquals(SdkInstrument.SO2, detail.getInstrument());
        assertEquals(SdkTriggerSource.MANUAL, detail.getTriggerSource());
        assertEquals(RECON_RECORD_TRIGGER_USER, detail.getTriggerUser());
        assertEquals(SdkExecutionStatus.FAILED, detail.getExecutionStatusName());
        assertEquals(3, detail.getExecutionStatus());
        // 手动终止收敛行无结构化失败原因（人读细节在 resultEvaluation），null 是如实呈现
        assertNull(detail.getFailureReason());
        assertEquals(RECON_RECORD_RESULT_EVALUATION, detail.getResultEvaluation());
        assertFalse(detail.getPhaseTimelines().isEmpty(), "锚点行落库了阶段时间线");
        assertFalse(detail.getRecordSnapshot().isEmpty(), "锚点行落库了触发参数快照");
    }

    @Test
    void getExecutionResult_assemblesFiveLayerStructureFromRealSubTables() {
        SdkExecutionResult result = sdk.getExecutionResult(RECON_RECORD_ID);

        assertEquals(RECON_RECORD_ID, result.getRecordId());
        assertEquals(SdkQcType.SPAN_CHECK, result.getQcType());
        assertEquals(SdkInstrument.SO2, result.getInstrument());
        assertEquals(SdkTriggerSource.MANUAL, result.getTriggerSource());
        assertEquals(SdkExecutionStatus.FAILED, result.getExecutionStatusName());
        assertNull(result.getFailureReason());
        assertNotNull(result.getJudgement(), "判定层三子表任一缺行也不得缺判定对象");
        assertTrue(result.getPhaseTimelines().size() >= 1, "过程层取 qcm_record_phase 子表");
        assertTrue(result.getKeyParameters().size() >= 1, "工况层取 qcm_record_key_param 子表");
        assertNotNull(result.getInstrumentName(), "溯源层仪器名称完成时冻结");
    }

    @Test
    void queryPlans_translatesPlanSideVocabulary() {
        List<SdkPlanSetting> all = sdk.queryPlans(null);
        assertFalse(all.isEmpty(), "锚点期部署库有 19 条计划，全量查询不得为空");
        boolean sawFinished = false;
        for (SdkPlanSetting setting : all) {
            assertNotNull(setting.getQcType(), "计划侧 qc_type 均 name 落库，必须全部译出");
            assertNotNull(setting.getScheduleType());
            assertNotNull(setting.getStatus());
            assertNotNull(setting.getInstruments());
            if (setting.getStatus() == SdkPlanStatus.FINISHED) {
                sawFinished = true;
            }
        }
        assertFalse(sawFinished, "queryPlans(null) 排除 FINISHED（已终结的一次性计划不属「当前设置」）");

        SdkPlanSetting multiZero = byId(all, RECON_MULTI_ZERO_PLAN_ID);
        assertEquals(SdkQcType.MULTI_ZERO_CHECK, multiZero.getQcType());
        assertEquals(Arrays.asList(SdkInstrument.SO2, SdkInstrument.NO2, SdkInstrument.CO, SdkInstrument.O3),
                multiZero.getInstruments());
        assertEquals(SdkPlanStatus.PAUSED, multiZero.getStatus());
        assertEquals(SdkScheduleType.DAILY, multiZero.getScheduleType());
        assertFalse(multiZero.isEnabled());
        assertEquals(0, multiZero.getHour());
        assertEquals(0, multiZero.getMinute());

        List<SdkPlanSetting> active = sdk.queryPlans(SdkPlanStatus.ACTIVE);
        assertFalse(active.isEmpty());
        for (SdkPlanSetting setting : active) {
            assertEquals(SdkPlanStatus.ACTIVE, setting.getStatus());
            assertTrue(setting.isEnabled());
        }
        SdkPlanSetting spanSo2 = byId(active, RECON_ACTIVE_SPAN_PLAN_ID);
        assertEquals(SdkQcType.SPAN_CHECK, spanSo2.getQcType());
        assertEquals(Arrays.asList(SdkInstrument.SO2), spanSo2.getInstruments());
        assertEquals(SdkScheduleType.DAILY, spanSo2.getScheduleType());
        assertEquals(6, spanSo2.getHour());
        assertEquals(48, spanSo2.getMinute());
        for (SdkPlanSetting setting : active) {
            if (setting.getPlanId() == RECON_MULTI_ZERO_PLAN_ID) {
                throw new AssertionError("PAUSED 计划不得出现在 ACTIVE 过滤结果里");
            }
        }
    }

    @Test
    void queryRunning_idleStateReturnsEmptyList() {
        assertTrue(sdk.queryRunning().isEmpty(), "无运行批次时必须如实回空（编排器 mock 空闲态）");
    }

    private static QcmRecord reconRecord() {
        QcmRecord anchor = recordMapper.selectById(RECON_RECORD_ID);
        assertNotNull(anchor, "侦察锚点行 qcm_record#" + RECON_RECORD_ID + " 已不在部署库，须重新侦察刷新期望值");
        assertEquals(RECON_RECORD_BATCH_ID, anchor.getBatchId(), "锚点行批次与侦察值不符");
        return anchor;
    }

    private static SdkPlanSetting byId(List<SdkPlanSetting> plans, long planId) {
        for (SdkPlanSetting setting : plans) {
            if (setting.getPlanId() == planId) {
                return setting;
            }
        }
        throw new AssertionError("计划 " + planId + " 不在结果里（部署库计划被清理？）："
                + planIds(plans));
    }

    private static List<Long> planIds(List<SdkPlanSetting> plans) {
        List<Long> ids = new ArrayList<>();
        for (SdkPlanSetting setting : plans) {
            ids.add(setting.getPlanId());
        }
        return ids;
    }

    /**
     * 下发 SQL 参数捕获器：拦在 Executor 层记录 statement id、命名参数与动态 SQL 结果，
     * 供「api 枚举 → 存储词汇」翻译方向对账（测试侧装置，不进生产）。
     */
    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static final class BoundSqlCaptor implements Interceptor {

        private final List<CapturedQuery> queries = new ArrayList<>();

        void clear() {
            queries.clear();
        }

        CapturedQuery byStatement(String statementId) {
            for (CapturedQuery query : queries) {
                if (query.statementId.equals(statementId)) {
                    return query;
                }
            }
            throw new AssertionError("本次调用未捕获到语句 " + statementId + "，已捕获：" + queries.size() + " 条");
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs()[1];
            BoundSql boundSql = statement.getBoundSql(parameter);
            queries.add(new CapturedQuery(statement.getId(), namedParams(parameter), boundSql.getSql()));
            return invocation.proceed();
        }

        /** 只留 @Param 命名键（去掉 mybatis 生成的 param1/param2 序号别名），便于按名对账。 */
        @SuppressWarnings("unchecked")
        private static Map<String, Object> namedParams(Object parameter) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (parameter instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) parameter).entrySet()) {
                    if (!entry.getKey().startsWith("param")) {
                        out.put(entry.getKey(), entry.getValue());
                    }
                }
            } else if (parameter != null) {
                out.put("self", parameter);
            }
            return out;
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(Properties properties) {
            // 无可配属性
        }

        static final class CapturedQuery {
            final String statementId;
            final Map<String, Object> params;
            final String sql;

            CapturedQuery(String statementId, Map<String, Object> params, String sql) {
                this.statementId = statementId;
                this.params = params;
                this.sql = sql;
            }
        }
    }
}
