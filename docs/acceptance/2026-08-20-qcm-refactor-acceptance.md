# qcm 重构 · 验收要求（质量控制对标文档）

> 版本：v1.0（2026-08-20）。本文档是 qcm 重构的**强制质量基准**：每个 Phase 门禁与终验以本文为准，与需求（`docs/requirements/`，FR/D 编号）和实施计划（`docs/plans/2026-08-20-qcm-refactor.md`，任务编号）三方互锁。
> 每条验收项编号 `AC-<域>-<序号>`；红线项 `RED-<序号>`（一票否决）。所有「静态扫描」均给出可执行命令与**预期零输出/零命中**，验收时原样执行留档。

---

## 1. 总则

1. **适用范围**：env-quality-control-manager 重构全部交付物（Java/vue/SQL/测试/文档/构建产物），及计划触及的 ruoyi 种子文件。
2. **验收层级**：
   - **L1 Phase 门禁**：每 Phase 结束逐项核对 §4 出口条件，未过不得进入下一 Phase；
   - **L2 任务级验收**：subagent 交付物按 §3 指标 + 对应 FR 评审，不合格打回；
   - **L3 终验**：全部 Phase 过门禁后按 §5 追溯矩阵逐项复核 + §6 审计项复核 + §7 流程执行。
3. **通过定义**：红线 0 触碰 + 量化指标全部 PASS + 追溯矩阵全覆盖（无 N/A 逃逸）+ 三类证据齐备（命令输出/测试报告/浏览器截图或 DOM 断言）。
4. **证据纪律**：每项 PASS 必须附可复现证据（命令 + 实际输出节选）；FAIL 必须三分类（代码缺陷/测试缺陷/环境问题）并记录处置；禁止以「跳过用例/放宽断言/加长等待」将 FAIL 洗成 PASS。

---

## 2. 红线（一票否决，任何一条触碰即整体验收不通过）

| # | 红线 | 检测方式 |
|---|---|---|
| RED-1 | 任何 REST 端点缺 `@PreAuthorize`（豁免清单必须为空） | §7 命令 C1 |
| RED-2 | mapper XML 存在 `${params.` 拼接 | 守卫测试 `MapperXmlInjectionGuardTest` |
| RED-3 | `catch (Throwable …)` 或空 catch 块或 `catch (Exception ignored)` 静默吞异常 | §7 命令 C2 |
| RED-4 | 测试源码出现 `Thread.sleep`（同步用途）；调度/异步测试必须确定性（Clock 注入/latch/虚拟时钟） | §7 命令 C3 |
| RED-5 | 任何测试被 skip/disable（`@Disabled`/`@Ignore`/assumeTrue 逃逸）或断言被削弱（diff 中断言只剩删减） | 测试报告 + git diff 审查 |
| RED-6 | 未经用户确认执行部署库写操作（DROP/DELETE/apply seed） | 过程记录（执行前必须存在用户确认记录） |
| RED-7 | 执行器执行了 git commit/push | git log 审查（交付期 HEAD 不变） |
| RED-8 | 猜测性兜底：未知分支返回 null/默认值掩盖、未接线类型被静默降级为单仪器执行 | 代码评审 + `EXECUTOR_TYPE_NOT_READY` 路径测试 |
| RED-9 | `@Deprecated` 方法被新代码调用；引入新 UI 库依赖；出现 `tw:` 前缀类名 | §7 命令 C4/C5 |

---

## 3. 量化指标

### 3.A 功能正确性（A）

| # | 指标 | 阈值 | 测量方法 / 证据 |
|---|---|---|---|
| AC-A0 | 重构目标清单（`docs/plans/2026-08-20-qcm-refactor-objectives.md`）：Phase 0 冻结基线；每门禁更新状态；终验全部条目 CLOSED 或 WAIVED（豁免经用户确认留档）；实现期新增项编号顺延且记录来源，清单外修复 = 0 | 基线冻结 + 终验归零 | 清单状态列 + 门禁记录 |
| AC-A1 | 需求追溯覆盖：FR-00~06 每条可指认实现任务与验收证据 | 100%（0 条无主） | §5 矩阵逐行核对 |
| AC-A2 | 调度语义测试：`ScheduleCalculatorTest` 全部边界用例（每日/每周跨周/每月缺日 2 月 31/多日升序/一次性过去与未来/恢复不追补/秒归零） | 100% PASS | mvnd 测试报告 |
| AC-A3 | 三源触发互斥：任一源执行中，其余两源 REJECTED 且写失败记录（failure_reason=EXECUTOR_BUSY_CONFLICT） | 3×2 组合全覆盖 | `QcmExecutionOrchestrator` 单测 |
| AC-A4 | ONCE 生命周期：触发后（成功/失败/终止/冲突四种收尾）状态=FINISHED，不再触发 | 4 用例 PASS | 单测 |
| AC-A5 | 批次：一次触发 N 条记录同 batch_id；终止任一条 → 批次全停；冲突拒绝也成批 | 用例 PASS + 浏览器记录页分组可见 | 单测 + Playwright |
| AC-A6 | 触发溯源：每条 qcm_record 的 trigger_user（MANUAL=登录名/SCHEDULED=system/REMOTE=sourceName）三态正确，记录页可见 | 3 用例 PASS | 单测 + 页面 DOM 断言 |
| AC-A7 | SDK 契约：trigger 受理/四类拒绝（含 allowQueue=true→QUEUE_NOT_SUPPORTED）/queryExecution 轮询终态/getRecordDetail 报告级全量数据（D15 清单逐字段） | 用例 PASS | `QualityControlSdk` 契约测试 |
| AC-A8 | 多仪器零点中间态：composer 未接线时触发 → N 条失败记录 EXECUTOR_TYPE_NOT_READY，不降级、不阻塞其他类型 | 用例 PASS | 单测 |
| AC-A9 | 重绑行为不变：现有 10 个测试类（7×Gen*ReportTest、ReportGeneratorTest、ZeroSpanDayPairSelectorTest、QualityControlExecutionLogHelperTest）全绿且断言零放宽 | 100% PASS + diff 审查 | mvnd 报告 |
| AC-A10 | 新类型/新表 seed：18 条计划入库且字段（类型/仪器/浓度 CO=40000 其余 400/PAUSED）与 06 §3 表逐行一致 | 18/18 | psql 查询比对 |
| AC-A11 | 旧种子清除：ruoyi 三 SQL 中 `env_quality_control` 与 `qualityControlTask.run`（job 21-41）零残留（job 15/42/43 保留） | 0 命中 | §7 命令 C6 |
| AC-A12 | 浏览器回归四旧页（records 列表/详情/报告预览/停止/导出、report 列表/导出、custom 立即执行、gas_setting 读写） | 全 PASS | Playwright（ignoreCache reload） |
| AC-A13 | 计划页全链路（创建 4 类调度→摘要文案 4 格式正确→启停→编辑重算→立即执行→冲突提示→一次性 FINISHED→删除） | 全 PASS | Playwright |

### 3.B 测试质量（B）

| # | 指标 | 阈值 | 测量方法 / 证据 |
|---|---|---|---|
| AC-B1 | 测试通过率（模块全量） | 100%，0 失败 0 跳过 | `mvnd test` 报告 |
| AC-B2 | 测试确定性：测试源码 `Thread.sleep` 命中 | 0 | §7 命令 C3 |
| AC-B3 | 新增核心类行覆盖：`schedule/`、`service/QcmExecutionOrchestrator`、`api/`、`service/PlanParamValidator` | ≥ 85% | jacoco（模块内临时启用，html 报告留档） |
| AC-B4 | 新增代码整体行覆盖（其余新增） | ≥ 70% | jacoco 同上 |
| AC-B5 | 全 reactor：workspace 根 `mvnd clean install` | BUILD SUCCESS 全模块绿 | 构建日志 |
| AC-B6 | 并发安全回归：`executorMap` 并发容器化后，调度器/编排器/SDK 并发用例（CountDownLatch 屏障）无丢失更新 | PASS | 单测 |
| AC-B7 | api 包零依赖：`api/` 下源码 import 不含 `ruoyi|springframework|ecat\.core|lombok` 之外的业务依赖（Lombok 编译期允许） | 0 违例 | §7 命令 C7 |

### 3.C 性能（C，本地/部署库基线，毫秒级铁律）

| # | 指标 | 阈值 | 测量方法 / 证据 |
|---|---|---|---|
| AC-C1 | qcm_record 列表首页（create_time DESC，PageHelper 10 条） | p95 < 100ms | psql `\timing` / EXPLAIN ANALYZE + REST 计时 |
| AC-C2 | batch_id / plan_id / (task_type,execution_status) 查询 | 各 < 50ms 且走索引 | EXPLAIN（0 Seq Scan） |
| AC-C3 | qcm_plan 全表操作（调度器 rearm 查询） | < 20ms | EXPLAIN ANALYZE |
| AC-C4 | 调度唤醒误差：计划点与实际触发时刻差 | < 1s | 单测注入 Clock + 集成日志时间戳抽样 |
| AC-C5 | SDK trigger 受理返回耗时（执行进行中与空闲两态） | < 100ms | 单测计时（Stopwatch） |
| AC-C6 | 报告列表不取 report_content 大列：列表 SQL 列集合 | 不含该列 | SQL 审查 + mapper 断言 |
| AC-C7 | 导出 1000 行记录：完成时间与内存 | < 5s，无 OOM/无全量单条 insert | 造数脚本 + JFR/日志计时 |
| AC-C8 | 报告生成 N+1 消除：参数级设备查询次数 | ≤ 参数个数（预取命中） | 计数桩断言 |
| AC-C9 | 超时查询复核：验收期任何 >1s 的 qcm 查询 | 0 条（出现即根因分析记录） | 验收记录 |

### 3.D 安全（D）

| # | 指标 | 阈值 | 测量方法 / 证据 |
|---|---|---|---|
| AC-D1 | `@PreAuthorize` 覆盖 | REST 方法 100%（controller 内 `@*Mapping` 方法数 = 注解数） | §7 命令 C1 人工比对 |
| AC-D2 | 鉴权实测：无 token / 无权限角色 / 正常用户三态 body code = 401/403/200 | 三态各 ≥1 端点实测（含 plan 新端点与 report 修复端点） | curl 8080 看 body code |
| AC-D3 | `@RequestBody Map` 残留 | 0（全部 DTO + 校验注解） | §7 命令 C8 |
| AC-D4 | 数值写设备前范围校验（GasSetting 浓度等） | 越界值被 400 拒绝 | 单测/接口实测 |
| AC-D5 | SQL 注入面 | 0（RED-2 守卫 + 新 XML 全 `#{}`） | 守卫测试 + 审查 |

### 3.E 代码规范（E，对标 rules/java.md + D16「优秀级」）

| # | 指标 | 阈值 | 测量方法 / 证据 |
|---|---|---|---|
| AC-E1 | 死代码：`System.out`、`printStackTrace`、注释掉的代码块、死 import/死字段、无 XML 的 mapper、未注册页面 | 0 | §7 命令 C9 + 审查 |
| AC-E2 | 吞异常（空 catch / ignored / Throwable） | 0 | §7 命令 C2 |
| AC-E3 | 实体 Lombok 化：新增实体手写 getter/setter | 0 个 | 审查 |
| AC-E4 | 新增方法 ≤ 80 行、新增类 ≤ 500 行；拆分后 ReportGenerator 单文件 ≤ 600 行、records/index.vue ≤ 800 行 | 100% | `awk`/wc 抽查 + 审查 |
| AC-E5 | 魔法值：质控类型/状态/触发类型标识 100% 枚举或常量（两套类型标识合一）；`"admin"`、裸 `3`、`40000f`、默认 `4.0` 收口 | 0 残留 | §7 命令 C10 |
| AC-E6 | Java 8 兼容：`var `、`switch` 表达式箭头、`List.of/Map.of/Set.of` | 0 命中（编译即证） | §7 命令 C11 |
| AC-E7 | 局部类型全限定名（`com.alibaba.fastjson2.JSONObject` 等作局部类型） | 0 | §7 命令 C12 |
| AC-E8 | 时间统一：新实体时间字段 100% `Instant`；新 DDL 100% timestamptz；`SimpleDateFormat` 残留 | 0 残留 | §7 命令 C13 |
| AC-E9 | 共享可变状态：非并发容器的跨线程共享字段 | 0 | 审查（含 executorMap 类似模式） |
| AC-E10 | 注释：不引用 `docs/` 路径；无 R1/P1 式代号；关键类有「为什么」注释 | 0 违例 | §7 命令 C14 + 审查 |
| AC-E11 | pom：junit4 依赖移除，测试栈统一 JUnit5 | 0 junit4 引用 | pom diff |

### 3.F 前端（F，D17）

| # | 指标 | 阈值 | 测量方法 / 证据 |
|---|---|---|---|
| AC-F1 | 时间选择组件 = `el-time-picker`/`el-date-picker`；多选 = `el-checkbox-group`；分步 = `el-steps` | 100%（自研时间控件 0） | vue 源码审查 |
| AC-F2 | 新增 UI 库依赖 | 0 | package.json diff |
| AC-F3 | `tw:` 前缀残留 | 0 | §7 命令 C15 |
| AC-F4 | dist 产物同步：改 .vue 后 `npm run release` 已跑，磁盘 dist mtime 晚于源文件 | 100% | ls -l 比对 + 浏览器 ignoreCache 实测新页面 |
| AC-F5 | 表单回退不丢数据（步骤 1→5→1 已填保留）；状态操作显隐矩阵（FR-01-23） | 浏览器实测 PASS | Playwright |
| AC-F6 | 冗余局部注册（宿主已全局注册的 element-plus 组件再 import） | 0 | §7 命令 C16 |

### 3.G 数据与交付物（G）

| # | 指标 | 阈值 | 测量方法 / 证据 |
|---|---|---|---|
| AC-G1 | 新库重灌三步（public.sql→qcm_data.sql→qcm_auth.sql）幂等重跑 ×2 | 0 报错 | psql 执行日志 |
| AC-G2 | 旧表/旧 job 在部署库消失、`qcm_auth` 菜单与权限生效 | 实测 | psql + 8081 菜单可见 |
| AC-G3 | DDL 与实体/mapper 列一一对应（列名/类型/可空） | 0 漂移 | 人工比对表 |
| AC-G4 | 四件套相互链接有效：需求(FR)→计划(任务)→目标清单(G)→验收(AC/RED) 双向可达 | 100% | 文档审查 |
| AC-G5 | 交付汇报含：修改内容、验证命令、验证结果、遗留问题（composer 接线/报告适配/HTTP 门面）四节 | 齐 | 汇报文档 |

---

## 4. Phase 门禁（L1 出口条件）

| Phase | 门禁（全部满足才放行） |
|---|---|
| 0 安全网 | **Task 0.0 目标清单冻结复核完成（AC-A0）**；RED-1/2 复核通过；4 修复落盘（对应目标项 CLOSED）；模块 `mvnd test` 绿；AC-D1 首测（report 端点三态） |
| 1 数据层 | AC-A9/A10/A11、AC-E1（死代码清零）、AC-G1（新库）、AC-A12（四页回归）、AC-E8（时间统一）；REST 契约 diff=0（路径/参数/返回结构） |
| 2 调度+页面 | AC-A2/4/5/6/13、AC-B2/B3、AC-C1..C4；misfire 单测（跳过不补跑+日志）；seed 第三参核对记录在案（AC-A10 补参数） |
| 3 SDK | AC-A7、AC-B7、AC-C5；跨集成 registry 冒烟记录 |
| 4 多零点 | AC-A8；前端类型显隐矩阵实测 |
| 5 收尾 | AC-B5（全 reactor）、AC-C6..C9、AC-E4/E5（拆分与收口）、AC-F6；§5 矩阵终检 + §7 验收记录签署 |

---

## 5. 追溯矩阵（FR → 验收项）

> 覆盖完整性基准：下表必须穷尽 FR-00~06 全部编号（区间表示验收方法相同）。终验时逐行打钩并填证据编号。

| 需求 | FR 区间 | 验收项 |
|---|---|---|
| 00 | §6 验收总则 5 条 | 本文档全篇（AC-G5 收口） |
| 01 调度 | FR-01-01..08（引擎） | AC-A2/B2/B6/C3/C4 |
| 01 | FR-01-09..14（方式语义） | AC-A2 |
| 01 | FR-01-15..19（生命周期） | AC-A4/A13、门禁2 |
| 01 | FR-01-20..25（列表页） | AC-A13/F5 |
| 01 | FR-01-26..30（表单+校验） | AC-A13/F1/F5、AC-B3（Validator 覆盖） |
| 01 | FR-01-31..35（时长参数） | AC-A13、预览区实测、AC-E5（默认值常量单源） |
| 01 | FR-01-36..37（数据流） | AC-A13 |
| 01 | FR-01-38..41（技术栈） | AC-F1..F6 |
| 02 执行 | FR-02-01..03（三源/链路） | AC-A6/B6 |
| 02 | FR-02-04..09（冲突/misfire） | AC-A3、门禁2（misfire 用例） |
| 02 | FR-02-10..15（多零点） | AC-A8、门禁4 |
| 02 | FR-02-16..18（批次） | AC-A5 |
| 02 | FR-02-19..21（终止） | AC-A5/A13 |
| 02 | FR-02-22..23（溯源/原因） | AC-A6 |
| 03 SDK | FR-03-01..03（形态） | AC-B7 |
| 03 | FR-03-04..09（trigger） | AC-A7/C5 |
| 03 | FR-03-10..13（异步/并发） | AC-A7/B6/C5 |
| 03 | FR-03-14..17（结果/报告/错误） | AC-A7（D15 字段清单逐项） |
| 04 数据 | FR-04-01..07（总则/plan） | AC-G1/G3/C3 |
| 04 | FR-04-08..11（record） | AC-G3/A5/A6 |
| 04 | FR-04-12..14（report） | AC-A9/G3 |
| 04 | FR-04-15..17（状态机） | AC-A4、门禁1（断电恢复用例） |
| 04 | FR-04-18..20（重绑） | AC-A9/A12、REST 契约 diff |
| 04 | FR-04-21（性能） | AC-C1..C9 |
| 05 权限 | FR-05-01..03（挂载/seed） | AC-G2/F4 |
| 05 | FR-05-04..06（权限串） | AC-D1/D2 |
| 05 | FR-05-07..09（菜单 seed） | AC-G1/G2 |
| 05 | FR-05-10..12（留痕） | AC-A6、@Log 记录抽查 |
| 05 | FR-05-13..14（测试纪律） | AC-D2 |
| 06 种子 | FR-06-01..04（旧 job） | AC-A11 |
| 06 | FR-06-05..06（旧表） | AC-G2 |
| 06 | FR-06-07..10（seed） | AC-A10 |
| 06 | FR-06-11..12（中间态） | AC-A8 |
| 06 | FR-06-13..14（部署验证） | AC-G1/G2/A12/A13 |

---

## 6. 审计项验收映射

> 逐项细化见目标清单（`../plans/2026-08-20-qcm-refactor-objectives.md`，G 编号与本节/计划任务三方对账）；本节保留汇总视图，门禁以目标清单状态列为准。

审计 S1/C1/P2→门禁0；S2→RED-2；S3/S5/S6→AC-D3/D4；S4→AC-D1 评审记录（单站模型结论留档）；C2/C3/C11→门禁1/2 状态机用例；C4→AC-A6（终态落库断言）；C5→AC-C7 计数上报；C6/C7→RED-3；C8→AC-D3；C9→AC-E9；C10→AC-C6；D1-D7→AC-E1；R1/R6→编排器评审（AC-B3）；R2/R3→AC-E4；N1→AC-E11；N2→AC-E3；N3→AC-E7；N4→AC-E5；N5→门禁1（STOPPING 全链路 grep 归零）；N6/N7→AC-E8/E10；P1/P3→AC-C6/C8；P4→AC-C7；P5→AC-C2；P6→评审；V1/V4/V5→AC-E4；V2→AC-F3；V3→保留项；V6→AC-F6。

---

## 7. 验收执行

### 命令集（在 `ecat-integrations/env-quality-control-manager` 执行，除非注明）

```
C1  鉴权覆盖：grep -rn "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping\|@RequestMapping" src/main/java --include=*Controller.java
    → 人工比对每个方法级 mapping 均有 @PreAuthorize（类级 @RequestMapping 不计）
C2  吞异常：grep -rnE "catch\s*\(\s*Throwable|catch\s*\([^)]+\s+ignored\s*\)" src/main/java ; grep -rn "catch" src/main/java -A1 | grep -B1 "^\S*-\s*}$" （空 catch）
    → 0 命中
C3  sleep：grep -rn "Thread.sleep" src/test/java
    → 0 命中
C4  Deprecated：grep -rn "@Deprecated" src/main/java 的被调用关系审查；新代码 grep -rn "@Deprecated" → 0 引用
C5  新 UI 库：git diff package.json（vue-modules）→ 仅版本号变化无新增依赖
C6  旧种子：grep -n "env_quality_control" ../ruoyi/sql/*.sql ; grep -n "qualityControlTask" ../ruoyi/sql/*.sql
    → 前者 0；后者仅 job 15 的 qualityControlGenReportTask
C7  api 零依赖：grep -rn "^import" src/main/java/*/api/ | grep -vE "java\.|lombok\." 
    → 0 命中
C8  Map 入参：grep -rn "@RequestBody Map" src/main/java
    → 0 命中
C9  死代码：grep -rn "System\.out\|printStackTrace" src/main/java ; 孤儿 mapper/页面人工清单
    → 0 命中
C10 魔法值：grep -rn '"calibration_check"' src/main/java ; grep -rn '"admin"' src/main/java | grep -v test
    → 0 命中（类型标识走枚举后）
C11 Java8：grep -rnE "\bvar\s+\w+\s*=|List\.of\(|Map\.of\(|Set\.of\(" src/main/java
    → 0 命中
C12 全限定名局部类型：grep -rnE "= new (com|org|java)\.[a-z.]+\.[A-Z]" src/main/java
    → 0 命中（import 后短名）
C13 时间统一：grep -rn "SimpleDateFormat" src/main/java ; grep -n "timestamp(6)\|timestamp without" src/main/resources/sql/
    → 0 命中
C14 注释引用 docs：grep -rn "docs/" src/main/java
    → 0 命中
C15 tw 前缀：grep -rn "tw:" src/main/resources/vue-modules/quality-control-manager/views/
    → 0 命中
C16 冗余注册：grep -rn "import { El" src/main/resources/vue-modules/quality-control-manager/views/
    → 0 命中（宿主全局注册）
```

### 执行顺序

1. 终验前置：全 Phase 门禁记录齐 → 2. 静态命令集 C1-C16 全跑留档 → 3. `mvnd clean install`（workspace 根）→ 4. jacoco 覆盖率报告 → 5. DB 重灌 + EXPLAIN 性能组 → 6. Playwright 全量（四旧页 + 计划页 + 冲突/终止场景）→ 7. 鉴权三态实测 → 8. §5 矩阵逐行签核 → 9. 交付汇报（AC-G5）。

### 验收记录模板（每 Phase 一份 + 终验一份）

```
## 验收记录 · <Phase/终验> · <日期>
| AC/RED | 结果 PASS/FAIL | 证据（命令+输出节选/报告路径/截图路径） | 备注（FAIL 三分类+处置） |
执行人/评审人/用户确认：____
```

### 签署

终验通过条件：红线 0 + §3 全 PASS + §5 矩阵 100% 勾 + §6 全勾 + 三件套文档齐（AC-G4）+ 用户人工检查确认（RED-7 语境下 commit 由用户执行）。
