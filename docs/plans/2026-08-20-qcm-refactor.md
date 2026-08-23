# qcm（env-quality-control-manager）大规模重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按需求集 `docs/requirements/`（v0.3，D1-D17）完成 qcm 重构：计划调度页 + 自建调度器 + 对外 SDK + 三表换代全量重绑 + 审计修复，性能/质量/规范达优秀级。

**Architecture:** 需求真相源 = `docs/requirements/00..06-*.md`（FR 编号引用出处）。分 6 个 Phase 顺序执行：安全网 → 数据层换代重绑 → 调度器+计划页 → SDK → 多仪器零点（qcm 侧契约） → 质量收尾。数据层 REST 契约保持不变，前端现有页面零改动；新能力（plan 页/SDK）增量接入。

**Tech Stack:** Java 8（禁 switch 表达式/List.of/var）、JUnit5 Jupiter + Mockito（禁 JUnit4 断言）、MyBatis XML、PG（timestamptz + Instant TypeHandler）、Lombok、动态 jar（@Service 非 @Component）、element-plus（宿主全局注册免 import）+ `qc:` tailwind。

**Iron Law 约束（覆盖默认流程）:**
- 全程**不执行 git commit / push**，每个 Phase 结束停 `[等待人工检查]`。
- 禁 `git reset/restore/clean/checkout --`、禁 pkill/killall。
- 测试禁 `Thread.sleep` 同步；调度测试用纯函数 + 注入 Clock。
- 改 .vue 后须 `npm run release` 重建 dist；浏览器回归用 `page.reload({ignoreCache:true})`。
- 触碰部署库（apply SQL）前先只读侦察并向用户确认。

**常用命令：**
- 模块测试：`cd ecat-integrations/env-quality-control-manager && mvnd test -Dtest=类名1,类名2`
- 全量：workspace 根 `mvnd clean install`（汇报完成前必须全绿）
- vue 构建：`cd src/main/resources/vue-modules && npm run release`
- 部署库：`psql "postgresql://postgres:sms%4014332m@47.94.6.129:5435/ecat"`（apply 前需用户确认）

---

## Phase 0 · 开工前安全网（目标清单冻结 + 审计高危项 4 修 + 1 测试）

### Task 0.0 重构目标清单复核与冻结（最高优先，先于一切代码修改）

**Files:** `docs/plans/2026-08-20-qcm-refactor-objectives.md`（已建 v1.0 基线，54 项目标）

- [ ] **Step 1** 对照需求 v0.3 全部 17 项决策（尤其后补的 D14 timestamptz / D15 SDK 报告数据 / D16 质量要求 / D17 前端基线）逐项复核清单：核对 G-VUE-4（el-time-picker 基线核对现有四页）、G-REQ-5（timestamptz 波及面）等后补决策是否引入新目标项，缺则补条目（编号顺延）。
- [ ] **Step 2** 全量复核 54 项的「归属任务」指向有效（计划任务存在且验收项编号可查），修正漂移。
- [ ] **Step 3** 冻结基线：清单头部标注「FROZEN @ Phase 0，后续只增不改（新增条目编号顺延+记录来源）」。
- [ ] **Step 4** 约定活文档纪律入执行流程：每 Phase 门禁更新状态列与状态汇总表；实现中发现清单外异味/漏洞必须先加条目再修（杜绝清单外修复）；终验 54 项 + 增项全部 CLOSED 或 WAIVED（豁免需用户确认）。

### Task 0.1 恢复 Report 控制器鉴权（审计 S1 / G-SEC-1，高危）

**Files:** Modify `src/main/java/com/ecat/integration/EnvQualityControlManagerIntegration/controller/EnvQualityControlReportController.java:41-110`

- [ ] **Step 1** 六个端点恢复 `@PreAuthorize`（权限串对齐现有风格）：

```java
@PreAuthorize("@ss.hasPermi('quality_control:report:list')")    // list/export 合用 list
@PreAuthorize("@ss.hasPermi('quality_control:report:query')")   // getInfo
@PreAuthorize("@ss.hasPermi('quality_control:report:add')")     // add
@PreAuthorize("@ss.hasPermi('quality_control:report:edit')")    // edit
@PreAuthorize("@ss.hasPermi('quality_control:report:remove')")  // remove
```

- [ ] **Step 2** 编译 + 现有测试绿：`mvnd test`（模块内全部）。Expected: BUILD SUCCESS。
- [ ] **Step 3** 运行时验证（core 已运行时）：无 token POST `/quality_control/report` on 8080 → HTTP 200 但 body `code=401`（ruoyi 惯例看 body code）。

### Task 0.2 修 SQL 注入（审计 S2，高危）

**Files:** Modify `src/main/resources/mapper/quality_control/EnvQualityControlRecordsMapper.xml:37`

- [ ] **Step 1** `end_time between '${params.beginEndTime}' and '${params.endEndTime}'` → `#{params.beginEndTime}` / `#{params.endEndTime}`。
- [ ] **Step 2** 补参数化回归测试 `MapperXmlInjectionGuardTest`：扫描 `src/main/resources/mapper/**/*.xml` 全部 mapper（Phase 1 换新 XML 后守卫继续生效），断言不含 `${params.`：

```java
@Test
void allMapperXmlMustNotUseDollarParamInterpolation() throws Exception {
    List<Path> xmls = Files.walk(Paths.get("src/main/resources/mapper"))
        .filter(p -> p.toString().endsWith(".xml")).collect(Collectors.toList());
    assertFalse(xmls.isEmpty(), "应至少存在一个 mapper XML");
    for (Path xml : xmls) {
        String content = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
        assertFalse(content.contains("${params."),
            xml + " 禁用 ${} 字符串拼接（SQL 注入，审计 S2）");
    }
}
```

- [ ] **Step 3** `mvnd test -Dtest=MapperXmlInjectionGuardTest` → PASS。

### Task 0.3 删错位 startPage（审计 P2）

**Files:** Modify `src/main/java/.../controller/GasSettingController.java:107-109`

- [ ] **Step 1** 删除 `startPage()`（内存组装后调用，PageHelper ThreadLocal 泄漏污染同线程后续查询）。
- [ ] **Step 2** `mvnd test` 绿。

### Task 0.4 executorMap 并发安全（审计 C1）

**Files:** Modify `src/main/java/.../EnvQualityControlManagerIntegration.java:46`

- [ ] **Step 1** `public Map<Long, AbstractCalibrationFlow> executorMap = new HashMap<>()` → `new ConcurrentHashMap<>()`。
- [ ] **Step 2** `mvnd test` 绿。

**Phase 0 完成标志：** 目标清单冻结复核完成 + 模块 `mvnd test` 全绿 + 4 项修复落盘（G-SEC-1/2、G-PERF-2、G-BUG-1 状态置 CLOSED 并附证据）。`[等待人工检查]`

---

## Phase 1 · 数据层换代与全量重绑（需求 04 / 06 全部 FR）

### Task 1.1 新表 DDL + seed（FR-04-01/05/06/08/12、FR-06-07）

**Files:** Create `src/main/resources/sql/qcm_data.sql`、`src/main/resources/sql/qcm_auth.sql`

- [ ] **Step 1** 写 `qcm_data.sql` 完整内容：

```sql
-- qcm 数据层换代（需求 D13/04）：旧 env_quality_control_* 三表废弃，本文件为唯一真相源。
-- 已部署库人工处置（执行前备份，需用户确认后执行）：
--   DELETE FROM sys_job WHERE job_id BETWEEN 21 AND 41;
--   DROP TABLE IF EXISTS env_quality_control_records;
--   DROP TABLE IF EXISTS env_quality_control_report;
--   DROP TABLE IF EXISTS env_quality_control_plan;

CREATE TABLE IF NOT EXISTS qcm_plan (
  id                 bigserial      PRIMARY KEY,
  plan_name          varchar(100)   NOT NULL,
  qc_type            varchar(50)    NOT NULL,
  instruments        jsonb          NOT NULL,   -- ["SO2"] / 多仪器零点 ["SO2","NO2","CO","O3"]
  schedule_type      varchar(20)    NOT NULL,   -- DAILY/WEEKLY/MONTHLY/ONCE
  schedule_config    jsonb          NOT NULL,   -- {hour,minute,weekdays[],monthDays[],onceMode,onceAt}
  concentration_ppb  numeric,
  flow_rate_lpm      numeric,
  duration_overrides jsonb,                     -- 稀疏：仅用户改过的时长参数
  point_percents     jsonb,                      -- [0,0.1,...] 0~1 小数
  status             varchar(20)    NOT NULL,   -- ACTIVE/PAUSED/FINISHED
  next_fire_time     timestamptz,
  last_fire_time     timestamptz,
  created_by         varchar(50)    NOT NULL,
  updated_by         varchar(50)    NOT NULL,
  create_time        timestamptz    NOT NULL DEFAULT now(),
  update_time        timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_qcm_plan_sched ON qcm_plan (status, next_fire_time);
COMMENT ON TABLE qcm_plan IS '质控任务计划（docs/requirements/04 §2）';

CREATE TABLE IF NOT EXISTS qcm_record (
  id                   bigserial    PRIMARY KEY,
  batch_id             varchar(36)  NOT NULL,
  plan_id              int8,
  task_type            varchar(50)  NOT NULL,   -- TaskTypeEnum: 0 自动/1 手动/3 远程
  quality_control_type varchar(50)  NOT NULL,
  parameter            text         NOT NULL,   -- 仪器/气体（NO2 展示名 NOx）
  start_time           timestamptz  NOT NULL,
  end_time             timestamptz,
  standard_value       numeric,
  monitoring_data      numeric,
  calculated_value     numeric,
  execution_status     int4         NOT NULL,   -- ExecutionStatusEnum 编码
  execution_log        text,                    -- 含 phaseTimelines（现状机制）
  result_evaluation    text,
  trigger_user         varchar(50)  NOT NULL,   -- MANUAL=用户名/SCHEDULED=system/REMOTE=sourceName
  failure_reason       varchar(50),             -- EXECUTOR_BUSY_CONFLICT / EXECUTOR_TYPE_NOT_READY
  record_snapshot      jsonb,                   -- 触发时计划配置快照
  created_by           varchar(50),
  updated_by           varchar(50),
  create_time          timestamptz  NOT NULL DEFAULT now(),
  update_time          timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_qcm_record_create      ON qcm_record (create_time DESC);
CREATE INDEX IF NOT EXISTS idx_qcm_record_batch       ON qcm_record (batch_id);
CREATE INDEX IF NOT EXISTS idx_qcm_record_plan        ON qcm_record (plan_id);
CREATE INDEX IF NOT EXISTS idx_qcm_record_type_status ON qcm_record (task_type, execution_status);
COMMENT ON TABLE qcm_record IS '质控执行记录（docs/requirements/04 §3）';

CREATE TABLE IF NOT EXISTS qcm_report (
  id                  bigserial     PRIMARY KEY,
  report_name         varchar(100)  NOT NULL,
  report_type         varchar(20)   NOT NULL,
  report_date         date          NOT NULL,
  instrument_name     varchar(100),
  instrument_no       varchar(50),
  gas_type            varchar(50)   NOT NULL,
  filer               varchar(50),
  reviewer            varchar(50),
  maintenance_company varchar(100),
  report_content      text,
  report_note         text,
  is_discarded        bool          NOT NULL DEFAULT false,
  created_by          varchar(50),
  updated_by          varchar(50),
  create_time         timestamptz   NOT NULL DEFAULT now(),
  update_time         timestamptz   NOT NULL DEFAULT now(),
  gas_source          varchar(50),
  gas_no              varchar(50)
);
COMMENT ON TABLE qcm_report IS '质控报告（docs/requirements/04 §4，列沿用旧表语义）';
```

- [ ] **Step 2** 同文件追加 18 条 seed（全部 PAUSED，next_fire_time NULL，created_by='system'；浓度 CO=40000、SO2/NO2/O3=400；`duration_overrides`/`point_percents` 暂空=全默认，Task 2.6 核对后补）：

```sql
INSERT INTO qcm_plan (plan_name, qc_type, instruments, schedule_type, schedule_config,
                      concentration_ppb, flow_rate_lpm, status, created_by, updated_by)
VALUES
('零点质控-全部仪器', 'multi_zero_check', '["SO2","NO2","CO","O3"]', 'DAILY', '{"hour":0,"minute":0}', NULL, NULL, 'PAUSED', 'system', 'system'),
('跨度核查-SO2', 'span_check', '["SO2"]', 'DAILY', '{"hour":0,"minute":0}', 400, 4.0, 'PAUSED', 'system', 'system'),
('跨度核查-NO2', 'span_check', '["NO2"]', 'DAILY', '{"hour":0,"minute":0}', 400, 4.0, 'PAUSED', 'system', 'system'),
('跨度核查-CO',  'span_check', '["CO"]',  'DAILY', '{"hour":0,"minute":0}', 40000, 4.0, 'PAUSED', 'system', 'system'),
('跨度核查-O3',  'span_check', '["O3"]',  'DAILY', '{"hour":0,"minute":0}', 400, 4.0, 'PAUSED', 'system', 'system'),
('线性核查-SO2', 'multi_check', '["SO2"]', 'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('线性核查-NO2', 'multi_check', '["NO2"]', 'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('线性核查-CO',  'multi_check', '["CO"]',  'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('线性核查-O3',  'multi_check', '["O3"]',  'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('精密度核查-SO2', 'precision_check', '["SO2"]', 'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('精密度核查-NO2', 'precision_check', '["NO2"]', 'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('精密度核查-CO',  'precision_check', '["CO"]',  'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('精密度核查-O3',  'precision_check', '["O3"]',  'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('准确度核查-SO2', 'accuracy_check', '["SO2"]', 'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('准确度核查-NO2', 'accuracy_check', '["NO2"]', 'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('准确度核查-CO',  'accuracy_check', '["CO"]',  'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('准确度核查-O3',  'accuracy_check', '["O3"]',  'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system'),
('转换效率检查-NOx', 'conversion_check', '["NO2"]', 'DAILY', '{"hour":0,"minute":0}', NULL, 4.0, 'PAUSED', 'system', 'system');
```

- [ ] **Step 3** 写 `qcm_auth.sql`：新增菜单「质控任务计划」（挂现有质控菜单同层，**先只读侦察** `SELECT menu_id, menu_name, parent_id FROM sys_menu WHERE menu_name LIKE '%质控%'` 锚定 parent_id）+ 按钮权限 `quality_control:plan:list/query/add/edit/remove/run`（模式对齐 `env-air-station-manager/src/main/resources/sql/asm_auth.sql`）。幂等（`WHERE NOT EXISTS` 或先 DELETE 再 INSERT）。

### Task 1.2 domain + TypeHandler 换代（FR-04-03 时间统一）

**Files:** Create `domain/QcmPlan.java`、`domain/QcmRecord.java`、`domain/QcmReport.java`、`support/TimestamptzInstantTypeHandler.java`；Delete `domain/EnvQualityControlRecords.java`、`domain/EnvQualityControlReport.java`

- [ ] **Step 1** `TimestamptzInstantTypeHandler`：拷贝 adm `env-air-station-manager/.../support/TimestamptzInstantTypeHandler.java` 为基线（同类同用，注明来源模块）。
- [ ] **Step 2** 三个实体：Lombok `@Data`，时间字段 `Instant`，jsonb 字段用 `String`（JSON 序列化由 service 层 Jackson 处理，mapper 传 String）。`QcmPlan` 含计算辅助字段 `scheduleSummary`（非持久化，service 生成人性化文案）。**旧实体 253 行手写 getter 样板随之消失（审计 N2）**。

### Task 1.3 mapper 换代（重绑映射表 = 完整信息）

**Files:** Create `mapper/QcmPlanMapper.java` + `mapper/quality_control/QcmPlanMapper.xml`、`mapper/QcmRecordMapper.java` + `QcmRecordMapper.xml`、`mapper/QcmReportMapper.java` + `QcmReportMapper.xml`；Delete 旧 Records/Report 两套、`mapper/EnvQualityControlCustomMapper.java`（审计 D1）

- [ ] **Step 1** 重绑映射（列名对齐，语义不变；XML 以旧两份为基线改表名/新列，`#{}` 全量核查）：

| 旧 | 新 |
|---|---|
| env_quality_control_records | qcm_record |
| env_quality_control_report | qcm_report |
| quality_control_plan_id | plan_id |
| （新）batch_id / trigger_user / failure_reason / record_snapshot | 新列直通 |
| timestamp(6) | timestamptz（resultMap 加 `typeHandler=TimestamptzInstantTypeHandler`，对齐 adm per-mapper 局部注册模式） |

- [ ] **Step 2** `QcmPlanMapper` 方法集（新）：`insert/selectById/selectList(筛选)/update/updateStatus/updateNextFireTime/deleteById`。
- [ ] **Step 3** `QcmRecordMapper` 增：`insertBatch(List)`（报告生成批插，审计 P4）、`selectByBatchId`、`updateRecordsExecutionStatus`（保留断电恢复路径）、`markStopInProgressClearEndTime`（沿用）。

### Task 1.4 服务层与 Task 重绑（含审计 C2/C3/C5/C11 修正）

**Files:** Modify `service/impl/EnvQualityControlRecordsServiceImpl.java`、`EnvQualityControlReportServiceImpl.java`、`EnvQualityControlCustomServiceImpl.java`、`tasks/EnvQualityControlTask.java`、`tasks/EnvQualityControlCustomTask.java`、`tasks/EnvQualityControlGenReportTask.java`、`util/ReportGenerator.java`、入口 `EnvQualityControlManagerIntegration.java`

- [ ] **Step 1** 实体/mapper 全量替换（旧→新，机械替换，不改行为）。
- [ ] **Step 2** 断电恢复修正（审计 C3）：启动仅将 `execution_status IN (等待/运行中/STOPING)` 的记录改 FAILED 且 catch 块**不得吞异常**——失败时 `log.error` + 继续注册任务（注册与恢复解耦，不允许半途而废还报启动成功）。
- [ ] **Step 3** STOPING 拼写修正（审计 N5）：枚举 `STOPING→STOPPING`，DB 值随新表直接用新串（无历史数据）；前端 records/index.vue 中该状态的字符串同步替换（grep `STOPING`）。
- [ ] **Step 4** 报告生成批插（审计 P4/C5）：`GenReportTask` 改 `insertBatch`，单条失败记入日志并**计数上报**（不静默丢）。
- [ ] **Step 5** `mvnd test`：现有 10 个测试类（含 7 个 Gen*ReportTest + ReportGeneratorTest）全绿——它们是重绑安全网，**禁止放宽断言**。

### Task 1.5 controller 重绑 + 输入治理（审计 S3/S5）

**Files:** Modify 四个 controller；Create `controller/dto/`（GasSettingDto、StopRequest）

- [ ] **Step 1** `@RequestBody Map` → DTO + `@Validated`（`@NotNull @Min @Max`）；GasSetting 返回改 `AjaxResult`；浓度写设备前范围校验（ppb>0，数值型）；catch 块补 `log.warn`（审计 S6）。
- [ ] **Step 2** **REST 契约（路径/参数/返回结构）保持不变**——前端四页零改动的关键；DTO 字段名与原 Map key 一致。

### Task 1.6 死代码清理（审计 D1-D7）

- [ ] **Step 1** 删：`EnvQualityControlCustomMapper`、`PatrolRunSettings.vue`/`PatrolCycleSettings.vue`（含 dist 引用核对）、Task 死字段（deviceRegistry/executor）、GenReportTask `@Component`+死字段、注释代码块、`System.out.println`/`printStackTrace` 全部换 log（`EnvQualityControlManagerIntegration.java:26-72` 等）、`IEnvQualityControlRecordsService` 两个死重载、双份 javadoc。
- [ ] **Step 2** `grep -rn "System.out\|printStackTrace\|STOPING" src/` 归零；`mvnd test` 绿。

### Task 1.7 ruoyi 种子清理（FR-06-01/05）

**Files:** Modify `ecat-integrations/ruoyi/sql/public.sql`、`public_no_shard.sql`、`public_migrate.sql`

- [ ] **Step 1** 删 sys_job INSERT id 21-41（保留 id 15/42/43 与字典项）。
- [ ] **Step 2** 删三张 env_quality_control_* 表 DDL/COMMENT/migrate 段（public_migrate.sql 对应 #14/#15 及 plan 段）。
- [ ] **Step 3** `grep -n "env_quality_control\|qualityControlTask" ruoyi/sql/*.sql` 复核：仅剩 job 15 的 `qualityControlGenReportTask` 与字典/代码生成器历史行（gen_table 不动）。

### Task 1.8 库切换与回归（FR-06-13）

- [ ] **Step 1** 向用户申请确认后 apply 部署库：DROP 旧三表 → `qcm_data.sql` → `qcm_auth.sql`（注意先停 core，属静默改数据）。
- [ ] **Step 2** workspace 根重启 core（cwd=根，core-integration-test skill），8081 浏览器回归四页：records 列表/详情/报告预览/停止、report 列表/导出、custom 立即执行、gas_setting（`page.reload({ignoreCache:true})`）。
- [ ] **Step 3** 新库验证 18 条 seed 计划可见（此时 plan 页未建，用 psql 直查 qcm_plan）。

**Phase 1 完成标志：** 模块 `mvnd test` 绿 + 四页浏览器回归通过 + 新表生效。`[等待人工检查]`

---

## Phase 2 · 调度器 + 计划页（需求 01 / 02 核心）

### Task 2.1 调度纯函数 ScheduleCalculator（FR-01-03/09/10/11/12/13/16，TDD）

**Files:** Create `schedule/ScheduleSpec.java`、`schedule/ScheduleCalculator.java`；Test `src/test/java/.../schedule/ScheduleCalculatorTest.java`

- [ ] **Step 1 先写失败测试**（用例即需求，全部给定 `after` 断言输出，无 sleep）：

```java
// 固定基准：after = 2026-08-21T09:30:15+08:00（含秒，验证秒归零与同分钟不触发）
@Test void daily_sameMinute_returnsTomorrow()      // DAILY 09:30 → 次日 09:30:00
@Test void daily_beforeTarget_returnsToday()       // DAILY 10:00 → 当日 10:00:00
@Test void weekly_nextSelectedDay()                // WEEKLY 周一/周三 02:00, after=周四 → 下周一 02:00
@Test void weekly_sameDayBeforeTime_returnsToday() // after=周一 01:00 → 当日 02:00
@Test void monthly_missingDaySkipsMonth()          // MONTHLY 31 日, after=2026-08-21 → 2026-08-31? 是；after=2026-09-01 → 2026-10-31（9 月无 31）
@Test void monthly_multiDaysAscending()            // {1,15}, after=05 日 → 15 日
@Test void once_future_returnsOnceAt()             // ONCE SCHEDULED 未来时刻 → 该时刻
@Test void once_past_returnsEmpty()                // ONCE 过去 → Optional.empty()（创建校验另有拦截，防御层）
@Test void resumeDoesNotBackfill()                 // 暂停期错过多个触发点，恢复后 next = 恢复时刻之后第一个（不追补）
```

- [ ] **Step 2 跑测试确认红**：`mvnd test -Dtest=ScheduleCalculatorTest` → FAIL（类不存在）。
- [ ] **Step 3 实现**（Java 8，ZoneId 常量注入，纯函数无 IO）：

```java
public final class ScheduleCalculator {
    /** 计算严格晚于 after 的下次触发时刻；秒恒 00。ONCE 已过返回 empty。 */
    public static Optional<Instant> nextFire(ScheduleSpec spec, Instant after, ZoneId zone) {
        ZonedDateTime cursor = after.atZone(zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        switch (spec.getType()) {
            case DAILY:   return nextDaily(spec, cursor);
            case WEEKLY:  return nextWeekly(spec, cursor);
            case MONTHLY: return nextMonthly(spec, cursor);
            case ONCE:
                Instant at = spec.getOnceAt();
                return at != null && at.isAfter(after) ? Optional.of(at) : Optional.empty();
            default: throw new IllegalArgumentException("未知调度类型: " + spec.getType());
        }
    }
    // nextDaily: 从 cursor 起逐日找 LocalDate.atTime(h,m) ≥ cursor 的第一个
    // nextWeekly: 逐日推进，dayOfWeek ∈ weekdays 即命中
    // nextMonthly: 逐日推进（上限 62 天），dayOfMonth ∈ monthDays 即命中——天然实现「缺日跳过」
}
```

（逐日推进实现简单且正确性可测，计划数几十条量级无性能问题；不引入日历反向推演复杂度。）
- [ ] **Step 4 跑测试确认绿**；再跑全模块测试确认无回归。

### Task 2.2 调度器 QcmPlanScheduler（FR-01-01/02/04/05/07/08）

**Files:** Create `schedule/QcmPlanScheduler.java`；Test `QcmPlanSchedulerTest.java`

- [ ] **Step 1 测试先行**（注入可控 Clock + 直接调包内方法，不起线程）：

```java
@Test void arm_schedulesEarliestPlan()            // 两个 ACTIVE 计划，只对最早者 schedule
@Test void fire_updatesNextFireTimeAndRearms()    // 触发后 plan.next_fire_time 前进、重新挂最早
@Test void pause_removesFromScheduling()          // PAUSED 计划不再参与 arm
@Test void startupPastDue_skipsWithoutBackfill()  // next_fire_time 已过 → 只重算+日志，不触发执行（misfire D1）
@Test void configChange_rearmsOnlyAffected()      // 更新单计划后重新 arm（锁内重挂）
```

- [ ] **Step 2 实现**（生命周期对齐 adm AsmStatRefreshScheduler：synchronized start 幂等、@PreDestroy 收口、onStart/onPause 挂集成入口；`ConcurrentHashMap<Long, ScheduledFuture>` 已挂任务表，重挂先 cancel）：

```java
public final class QcmPlanScheduler {
    public static final Duration DRIFT_AUDIT_PERIOD = Duration.ofSeconds(30); // FR-01-07 兜底巡检
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
        r -> { Thread t = new Thread(r, "qcm-plan-scheduler"); t.setDaemon(true); return t; });
    // synchronized rearm(): SELECT status='ACTIVE' AND next_fire_time IS NOT NULL ORDER BY next_fire_time LIMIT 1
    //   → executor.schedule(() -> fire(planId), delayMs) ；fire(): 触发统一入口（Task 2.4）
    //   → 更新 next_fire_time = nextFire(spec, now)（FINISHED 则不再算）→ rearm()
    // 30s 巡检线程：比对 DB 最早 next_fire_time 与已挂任务，漂移则 rearm（防漏挂）
}
```

- [ ] **Step 3** misfire 路径：fire 时若 `next_fire_time < now - 60s`（时钟阈值）→ 不执行、重算、`log.warn`（含 plan id 与错过时刻）。

### Task 2.3 统一触发入口 + 冲突留痕（FR-02-01..08/16/17/22/23，审计 R1/C4/R6）

**Files:** Create `service/QcmExecutionOrchestrator.java`（合并两 Task 同构 70% 的编排模板，审计 R1）；Modify 两个 Task 改为薄委托；Create `util/BatchIds.java`

- [ ] **Step 1** 编排器单入口：

```java
/** 三源统一触发入口：SCHEDULED/MANUAL/REMOTE 殊途同归（FR-02-01） */
public BatchResult triggerExecution(QcExecutionRequest req, TriggerSource source, String triggerUser) {
    // 1. 校验（PlanParamValidator，REST/SDK 共用单一事实源 FR-03-17）
    // 2. 批次 batchId=UUID；每仪器一条 qcm_record（含 record_snapshot、trigger_user）
    // 3. 互斥闸（synchronized on 全局锁，与 composer isRunning 双查）：
    //    忙 → N 条记录置失败 failure_reason=EXECUTOR_BUSY_CONFLICT，返回 REJECTED(BUSY_CONFLICT)+recordIds（D10 三类都写）
    //    空闲 → 走现有 execute 链（flowParams 组装：浓度/流量/duration_overrides），结果回调统一落库（修 C4：异常不 throw 进 future，改写终态记录）
    // 4. ONCE 计划触发后置 FINISHED（D11）；更新 plan.last_fire_time/next_fire_time
}
```

- [ ] **Step 2** `EnvQualityControlCustomTask` 并入编排器后删除或薄化为兼容壳（保留 ruoyi-quartz 42/43 手动任务的调用路径不破坏——它们走 `getTask("EnvQualityControlCustomTask")`）。
- [ ] **Step 3** 批次终止扩展（FR-02-20/21）：`stopEnvQualityControlRecords` 识别记录所属 batch_id → 终止 flow 一次 → 批次内全部记录走 STOPING→终止落库；终止操作 `@Log` + 记录操作用户。
- [ ] **Step 4** 测试：三源互斥（一源执行中另两源 REJECTED+留痕）、批次 N 条、批次终止（终止一条=批次全停）、ONCE FINISHED、快照内容、失败原因枚举。
- [ ] **Step 5** REST `POST /quality_control/plan/run/{id}`（MANUAL，`SecurityUtils.getUsername()`）接编排器。

### Task 2.4 计划 REST + 校验器（FR-01-15..19/30/36/37，FR-05-04/10/12）

**Files:** Create `controller/QcmPlanController.java`（前缀 `/quality_control/plan`）、`service/QcmPlanService.java` + Impl、`service/PlanParamValidator.java`

- [ ] **Step 1** 端点：`GET list（分页+状态/类型筛选）/ GET {id} / POST / PUT / DELETE / PUT status/{id}/{enable|pause} / POST run/{id}`；`@PreAuthorize` 六权限串（05 §2）；`@Log(title="质控任务计划")`；caller 经 `*ForCaller` 传递（adm 模式）。
- [ ] **Step 2** `PlanParamValidator` 规则=FR-01-30 全表（名称非空/时刻合法/多选非空/ONCE 未来/浓度>0/流量(0,50]/百分比 0~1 升序≥2/时长参数正整数秒/类型×仪器矩阵：multi 仅 multi_zero_check 可>1、conversion 锁 NO2）。校验失败逐字段返回（AjaxResult error 带字段与原因）。
- [ ] **Step 3** service：CRUD 后通知调度器 rearm（新增/编辑/启停/删除四路径）；调度摘要文案生成（FR-01-21 四格式）。
- [ ] **Step 4** 测试：Validator 全规则 + service 状态机转移（非法转移拒绝：FINISHED 不可 pause 等）。

### Task 2.5 前端计划页（FR-01-20..26/38..41，D17）

**Files:** Create `src/main/resources/vue-modules/quality-control-manager/views/quality_control/plan/index.vue`（列表）+ `PlanEditDialog.vue`（五步表单）；Modify `vue-modules/module-config.json`（+quality_control_plan 路由）、`api/quality_control.js`（+plan API 封装）

- [ ] **Step 1** 组件基线（D17）：`el-steps`（5 步）/`el-radio-group`（调度方式）/`el-time-picker format="HH:mm"`（时:分）/`el-date-picker type="datetime"`（一次性）/`el-checkbox-group`（周几/几号/多仪器）/`el-select`（类型，转换效率锁 NOx）/`el-table`+`el-tag`（列表/状态徽章）/`ElMessageBox.confirm`（二次确认）；样式沿用 `qc:` 前缀 tailwind，禁止 `tw:` 与新 UI 库。
- [ ] **Step 2** 五步内容（FR-01-26 表）：调度→类型+仪器（含名称）→浓度（类型条件显隐）→流量+校准仪只读展示（名称经现有接口取）→时长参数（预填 composer 默认值常量表 + 右侧预估只读区）。
- [ ] **Step 3** 预估时长联动：调 qcm 新增 `GET /quality_control/plan/estimate?type=&overrides=`（service 委托 composer 阶段预估，公式同源 FR-01-32），参数变更即刷新。
- [ ] **Step 4** 列表操作区按状态显隐（FR-01-23）；立即执行确认框展示参数摘要（FR-01-24）。
- [ ] **Step 5** `npm run release` → `mvnd install` → 重启 core → 8081 实测新页（ignoreCache reload）。

### Task 2.6 seed 第三参核对（OPEN-09→落地）

- [ ] **Step 1** 读 `EnvQualityControlTask` 对 `calculatedValue` 参数的消费逻辑，确认 multi/precision/accuracy/conversion 的 `'1'` 语义；据实补 qcm_data.sql 中 8 条计划的 `duration_overrides`/`point_percents`；在 seed 文件头注释记录核对结论。

### Task 2.7 Playwright 回归（计划域全链路）

- [ ] **Step 1** 用例：创建四类调度各一条→摘要文案正确；启停状态流转；编辑重算下次触发；立即执行→记录产生（trigger_user=登录名）；执行中再次立即执行→失败记录+提示 EXECUTOR_BUSY_CONFLICT；一次性完成置 FINISHED；删除确认。
- [ ] **Step 2** `mvnd clean install`（workspace 根）全绿。

**Phase 2 完成标志：** 调度确定性测试绿 + 浏览器全链路回归过。`[等待人工检查]`

---

## Phase 3 · 对外 SDK（需求 03 全部 FR）

### Task 3.1 api 包契约（FR-03-01/02/04/05/06）

**Files:** Create `api/QualityControlSdk.java`、`api/SdkTriggerRequest.java`、`api/SdkTriggerReply.java`、`api/SdkBatchState.java`、`api/SdkRecordDetail.java`

- [ ] **Step 1** 接口与 DTO（Lombok `@Value` + `@Builder`，Java 8；零 ruoyi/Spring/core import）：

```java
public interface QualityControlSdk {
    /** 异步触发：立即返回受理/拒绝，不等待执行（FR-03-10） */
    SdkTriggerReply trigger(SdkTriggerRequest request);
    /** 批次状态与各记录摘要（轮询，FR-03-11/12） */
    SdkBatchState queryExecution(String batchId);
    /** 单条记录全量明细：外部可独立生成报告（D15/FR-03-14/15） */
    SdkRecordDetail getRecordDetail(long recordId);
}
```

`SdkTriggerRequest` 字段=FR-03-05 表（qcType/instruments/concentrationPpb/pointPercents/flowRateLpm/durationOverrides/sourceName/allowQueue）；`SdkTriggerReply`：`accepted` + `batchId` + `recordIds` + `reason`(枚举: BUSY_CONFLICT/QUEUE_NOT_SUPPORTED/INVALID_PARAM/EXECUTOR_TYPE_NOT_READY) + `message`。

### Task 3.2 SDK 实现 + 获取入口（FR-03-03/07..17）

**Files:** Create `service/QualityControlSdkImpl.java`（@Service）；Modify `EnvQualityControlManagerIntegration.java`（+`getQualityControlSdk()`）

- [ ] **Step 1** Impl：trigger→`QcmExecutionOrchestrator.triggerExecution(req, REMOTE, sourceName)`（毫秒级返回，执行异步）；allowQueue=true→QUEUE_NOT_SUPPORTED（不进互斥闸）；queryExecution→batch 聚合；getRecordDetail→qcm_record 全量 + record_snapshot + phaseTimelines 解析（报告级数据 D15）。
- [ ] **Step 2** 注册：入口类 `getQualityControlSdk()`（registry 获取模式，对齐 adm `AirStationSdk.java:15-21`）。
- [ ] **Step 3** 测试：api 包零依赖守卫（反射断言 api 包类的 imports 无 ruoyi/spring/ecat.core——单测扫源码文件）+ 契约用例（受理/四类拒绝/轮询终态/allowQueue=true）+ 并发调用收敛同一闸。
- [ ] **Step 4** 跨集成冒烟：临时在现有测试里经 registry 取 SDK trigger 一次 span_check（mock composer）。

**Phase 3 完成标志：** SDK 契约测试绿 + api 零依赖守卫过。`[等待人工检查]`

---

## Phase 4 · 多仪器零点类型 qcm 侧契约（FR-02-10..15，OPEN-03 命名落地）

### Task 4.1 类型与编排支持（EXECUTOR_TYPE_NOT_READY 中间态）

**Files:** Modify `util/QualityControlTypeEnum.java`（+MULTI_ZERO_CHECK）、`service/QcmExecutionOrchestrator.java`、`service/PlanParamValidator.java`、前端类型下拉

- [ ] **Step 1** 枚举 +`MULTI_ZERO_CHECK("multi_zero_check", "多仪器零点质控")`；Validator 规则：instruments 1~4、无浓度无流量、仅零点类时长参数。
- [ ] **Step 2** 编排器：该类型触发时若 composer 无对应 ExecutorType → N 条记录失败 `EXECUTOR_TYPE_NOT_READY`（复用启动失败留痕路径），不影响其他类型；**不猜测兜底为单仪器**（严格模式）。
- [ ] **Step 3** composer 新 flow 契约注释写在编排器 javadoc（execute(instruments[], zero 参数)→每仪器一份阶段时间线与结果），供后续接线任务对照。
- [ ] **Step 4** 测试：类型校验矩阵 + NOT_READY 路径 + seed 中该类型计划触发产生 4 条失败记录（mock）。
- [ ] **Step 5** 前端：类型下拉 +1 项、零点类仪器多选显隐（FR-01-27/28）。

**Phase 4 完成标志：** 新类型全链路（配置→校验→触发→失败留痕）测试绿。`[等待人工检查]`

---

## Phase 5 · 质量收尾（D16 优秀级）

### Task 5.1 报告链路性能（审计 P1/P3/C10）

- [ ] **Step 1** report 列表 SQL 去掉 `report_content` 大列（列表页不解析 JSONB，详情单独查）——findPage 循环反序列化与 ensureQcStamp 修补下沉到生成侧（C10）。
- [ ] **Step 2** 导出改分批流式（PageHelper `PageInfo` 循环 or 游标），禁止全量载入。
- [ ] **Step 3** ReportGenerator 设备查询预取缓存（参数→attr Map 一次取全，消 N+1）。
- [ ] **Step 4** 基准：mock 1000 行记录生成报告耗时断言上限（防回归测试，非绝对值要求）。

### Task 5.2 规范与结构收尾（审计 R3/R2/N1/N4/N7/V1/V4/V5/V6）

- [ ] **Step 1** pom 删 junit4 依赖（N1）；`SimpleDateFormat`→`DateTimeFormatter`（N7，GenReportTask:44、ReportGenerator:919/1888）。
- [ ] **Step 2** 魔法值收口（N4）：`"admin"`/状态 `3`/`"calibration_check"`/40000f/4.0 → 常量或枚举（`QualityControlTypeEnum` 与 ReportGenerator 的类型标识两套合一）。
- [ ] **Step 3** ReportGenerator 拆分（R3）：7 个内部 Gen*Report 类提取为独立文件，`constructReportContent()` 模板上提基类；现有 7 个 Gen*ReportTest 保持绿（行为不变证明）。
- [ ] **Step 4** vue 拆分（V1/V4/V5）：records/index.vue 的报表预览弹窗拆子组件、PDF 导出抽 composable（与 report 页共用）、audit_span_check 状态轮询抽 composable；删冗余局部注册（V6）。
- [ ] **Step 5** `constructResult` ZERO/SPAN 分支合并（R2）。

### Task 5.3 终验（Definition of Done）

- [ ] **Step 1** workspace 根 `mvnd clean install` 全 reactor 绿。
- [ ] **Step 2** 全量 Playwright 回归：四旧页 + 计划页全链路（Phase 2.7 用例复跑）+ 新类型中间态。
- [ ] **Step 3** 性能预算核对：`EXPLAIN` 三新表主查询走索引；导出/列表耗时记录在案（FR-04-21）。
- [ ] **Step 4** 交付汇报：修改内容/验证命令/结果/遗留问题（composer 新 flow 接线、报告对新类型适配、HTTP 门面——均为既定 OUT 项）。

---

## 审计项 → 任务映射（防丢项）

| 审计 | 任务 | | 审计 | 任务 | | 审计 | 任务 |
|---|---|---|---|---|---|---|---|
| S1 | 0.1 | | C5/P4 | 1.4 | | N4 | 5.2 |
| S2 | 0.2 | | C6/C7 | 1.4/5.2 | | N5 | 1.4 |
| S3/S5/S6 | 1.5 | | C8 | 1.5 | | N6/N7 | 5.2 |
| S4 | 1.5 评估 | | C9 | 2.3 | | P1/P3 | 5.1 |
| C1 | 0.4 | | C10 | 5.1 | | P2 | 0.3 |
| C2/C3/C11 | 1.4/2.3 | | C/D1-D7 | 1.6 | | P5 | 1.1（DDL 索引） |
| C4 | 2.3 | | R1/R6 | 2.3 | | P6 | 2.3 |
| R2/R3 | 5.2 | | N1/N2 | 1.2/5.2 | | V1-V6 | 5.2 |

## 需求覆盖对照（FR → 任务）

00 验收总则→5.3；01 §1→2.1/2.2、§2→2.1、§3→2.2/2.4、§4-5→2.5、§6→2.5/2.6、§7→2.3/2.4、§8→2.5；02 全→2.3（§3 多零点→4.1）；03 全→3.1/3.2；04 全→1.1-1.4（§7→1.5/5.1）；05 全→1.1(auth)/2.4/2.5；06 全→1.1/1.7/1.8/2.6。
