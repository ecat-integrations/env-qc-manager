# qcm 重构 · 现有代码重构工作目标清单（活文档）

> 版本：v1.1（2026-08-20，由代码审计 + 需求差距盘点生成；Phase 0 Task 0.0 复核后冻结）。
> **FROZEN @ Phase 0（Task 0.0 复核通过）：后续只增不改——新增条目编号顺延 + 记录来源（哪次门禁/哪个 Task 发现）；对既有条目只允许更新「状态」列与证据。**
> **用途**：本清单是重构期间的对账基准——实施计划（`2026-08-20-qcm-refactor.md`）与验收基准（`../acceptance/2026-08-20-qcm-refactor-acceptance.md` §6）引用本清单编号；每个 Phase 门禁复核「状态」列；终验要求全部关闭（CLOSED）或显式豁免（WAIVED + 理由留档）。
> 编号：`G-<域>-<序号>`；状态取值 `OPEN → CLOSED / WAIVED`。
> 盘点基准：需求 v0.3（D1-D17、FR-00~06）+ 验收基准（AC/RED）+ 2026-08-20 深度代码审计。

---

## 使用规则

1. 任何人（含 subagent）在实现中发现新异味/漏洞 → **先加条目再修**（编号顺延），不允许清单外修复。
2. 每 Phase 门禁：逐条更新状态 + 证据（commit 前的工作区 diff 路径 / 测试名 / 命令输出）。
3. 豁免必须写明理由并经用户确认（对话记录为准），终验汇总 WAIVED 项。

---

## 1. G-SEC · 安全目标

| # | 现状证据 | 目标态 | 归属 | 状态 |
|---|---|---|---|---|
| G-SEC-1 | Report 控制器 6 端点 @PreAuthorize 全被注释（`EnvQualityControlReportController.java:41-98`） | 全端点恢复 `quality_control:report:*` 鉴权；AC-D1/D2 三态实测 | Task 0.1 / AC-D1、RED-1 | CLOSED（Task 0.1：6 端点恢复，list/export=list、query/add/edit/remove 各自；mvnd 50 绿；运行时三态见门禁0记录） |
| G-SEC-2 | records 列表 `end_time between '${...}'` 注入面（`EnvQualityControlRecordsMapper.xml:37`） | 全 mapper 0 `${}`，守卫测试防复发 | Task 0.2 / RED-2 | CLOSED（Task 0.2：两处改 #{}；MapperXmlInjectionGuardTest 目录级守卫+反向注入验证红→绿；全目录 `${` 归零） |
| G-SEC-3 | `@RequestBody Map` 直取（records stop / gas add / custom :37-44），缺 key 即 NPE、`(int)` 强转 CCE | 全部 DTO + `@Validated`，字段级错误信息 | Task 1.5 / AC-D3、RED 相关 | CLOSED（T1.5 三处 @RequestBody Map→DTO+@Validated；前端提交类型实证兼容） |
| G-SEC-4 | 按 id 操作无归属校验（records/report edit/remove） | 单站部署模型结论留档 + 评审记录（不引入过度设计） | Task 1.5 评审 / AC-D1 备注 | CLOSED（T5.2 B10：单站单租户部署模型结论落两 controller 类 javadoc，多租户演进路径注明） |
| G-SEC-5 | GasSetting 裸 HashMap 返回 + 浓度无范围校验直写设备（:113/:165） | AjaxResult + 数值范围校验 + 越界 400 | Task 1.5 / AC-D4 | CLOSED（T1.5 AjaxResult+浓度范围校验+400；测试 5 用例） |
| G-SEC-6 | GasSetting catch 吞设备异常丢堆栈（:60） | log.warn 留痕 | Task 1.5 / AC-E2 | CLOSED（T1.5 catch 补 log.warn 含设备标识） |

## 2. G-BUG · 正确性目标

| # | 现状证据 | 目标态 | 归属 | 状态 |
|---|---|---|---|---|
| G-BUG-1 | `executorMap` 非线程安全 HashMap 三方并发读写（`EnvQualityControlManagerIntegration.java:46`） | 并发容器；全模块共享可变状态 0 隐患 | Task 0.4 / AC-E9、AC-B6 | CLOSED（Task 0.4：ConcurrentHashMap + HashMap import 清除；同类隐患排查仅余 parameterMap 死字段→归 G-DEAD-3） |
| G-BUG-2 | stop 流程 STOPING 无收敛路径（不在 executorMap 即永久中间态） | 新状态机：中间态必达终态（含断电恢复一致性） | Task 1.4/2.3 / 门禁1-2 | CLOSED（T2.3 收敛修复 + 浏览器实测：STOPPING→1 分钟→FAILED「流程被用户手动终止」+end_time） |
| G-BUG-3 | 断电恢复无差别改 FAILED + 启动 catch 吞异常半途而废（Integration:46-61） | 仅非终态改失败；恢复与任务注册解耦，失败不得吞 | Task 1.4 / RED-3、门禁1 | CLOSED（T1.4 恢复收窄 in(0,1,4)+解耦不吞；重启实证无失败日志） |
| G-BUG-4 | future 回调内 throw 被吞（Task:473、CustomTask:369） | 终态统一落库回调，异常写记录 | Task 2.3 / AC-A6 | CLOSED（T2.3 回调异常落库终态不重抛，D-2 修复含裸结果 stub 路径） |
| G-BUG-5 | 报告逐条 save 失败仅 printStackTrace 继续（GenReportTask:122-127） | 批插 + 失败计数上报，0 静默丢 | Task 1.4/5.1 / AC-C7 | CLOSED（T1.4 saveBatch 批插+失败摘要+计数日志） |
| G-BUG-6 | `catch (Exception ignored)` / 7 处 `catch (Throwable ignored)`（PhasePayload:61-66、ReportGenerator 7 处） | 0 吞异常（RED-3），降级路径显式 log | Task 1.4/5.2 / RED-3 | CLOSED（T5.2 B7 + 终验补：Throwable ignored×7 → Exception+logger.debug 降级留痕，Error 不再吞；ignored 归零 grep） |
| G-BUG-7 | custom 控制器数字强转 CCE/NFE 500（:42-44） | DTO 反序列化 + 400 | Task 1.5 / AC-D3 | CLOSED（T1.5 AuditSpanCheckDto+Jackson 数值转换，非法串→400；前端实证发 JSON number 不触雷） |
| G-BUG-8 | Task 可变实例字段惰性初始化无同步（Task:36-37） | 随编排器重写消除（构造注入） | Task 2.3 / AC-E9 | CLOSED（T5.2 B9：三 Task 惰性 mry 字段删除，改执行期局部取 bean） |
| G-BUG-9 | report 列表循环 JSON 修补与详情不一致（ReportServiceImpl:33-50） | 修补下沉生成侧，读路径两侧一致 | Task 5.1 / AC-C6 | CLOSED（T5.1：ensureQcStamp 读路径修补删除（实证死代码：生成侧已写 stamp；report_display_type 恒空串改 report_type 映射）） |
| G-BUG-10 | 跨多次提交无事务边界，中途崩溃留孤儿记录 | 状态机 + 断电恢复收敛（不引入重分布式事务） | Task 1.4/2.3 / 门禁1-2 | CLOSED（T2.3 insertBatch 单语句事务=创建原子 + 断电恢复收敛） |
| G-BUG-11 | （T1.8 发现）动态注册别名只认 BaseEntity 子类（DynamicJarLoader.registerMappers 只注册 BaseEntity.isAssignableFrom 的 domain 类）；qcm Instant 纯净实体无别名 → XML parameterType/resultMap type 别名解析 CNFE → **整个集成动态注册失败**（REST/页面全 404）+ onStart 恢复 NPE（getSpringBean 取不到） | XML 实体引用一律 FQCN（对齐 adm Asm*Mapper 先例），不依赖别名注册；qcm 局部修复不动共享基建 | Task 1.8 修复 / AC-A12 | CLOSED（T1.8 发现并修复：XML 实体引用别名→FQCN 对齐 adm 先例；重启后 REST 200+页面渲染实证） |
| G-BUG-12 | （T2.7 发现）QcmPlanMapper.insert 列块 scheduleType 有 if 而值块缺失 → 13 列 12 值 SQL 报错（mock mapper 测不到 SQL 形状） | 修复 + MapperXmlInjectionGuardTest 增 insert 列/值 if 对称性静态守卫 | T2.7 修复 / 守卫测试 | CLOSED |
| G-BUG-13 | （T2.7 发现）Spring bean 方法签名/lambda 合成方法/返回类型引用 composer 类型（ExecutorResultBase 等）→ ruoyi 类加载器内省 getDeclaredMethods CNFE → bean 创建失败整链 404；另 PlanParamValidator 误用 @Component（动态 jar 只注册 @Service） | bean 签名一律 Object 化+体内强转（对齐旧 Task 体 内引用模式）；@Component→@Service；javap 全 bean 类描述符扫描零 composer 残留 | T2.7 修复 | CLOSED |
| G-BUG-14 | （T2.7 发现）insertBatch 全列写入而 buildRecords 未设 update_time → NOT NULL 违例 | buildRecords 补 createTime/updateTime 显式赋值 | T2.7 修复 | CLOSED |
| G-BUG-15 | （用户 review 发现）每月日期 checkbox 勾选态完全不渲染（element-plus checked prop 非受控，手搓 :checked+@change 无 v-model 回写渠道；数据入 model 但 UI 永不显示） | 改与每周组同构：group v-model + :value，删手搓三件套 | 变更批 2026-08-21 | CLOSED（变更批：checkbox 组改 v-model+:value 同构；浏览器实证 1/15/28 勾选渲染、未点 2 号 false） |
| G-BUG-16 | （用户 review 发现）buildFlowParams 的 zero/span 分支不透传 durationOverrides（平移旧代码保留的旧限制，违反 FR-01-34）——保存进 DB 但 estimate 与真实执行均不生效 | zero/span 分支补 putAll；TDD 红→绿（采样 3→20 预估差 510s） | 变更批 2026-08-21 | CLOSED（变更批 TDD 红→绿：zero/span 分支补 putAll；浏览器实证采样 3→20 总预估 29分01→37分31（+8分30=17×30s 精确）；estimate 与真实执行同时修复） |
| G-REQ-9 | （D19 需求变更）流量全类型可设（含零点，默认 4.0）；浓度展示细化：零点显示只读 0ppb、多点/准确度显示每阶段绝对浓度（composer 只读 estimatePoints，后端统一单位：CO=ppm 其余=ppb）；时长步加重置默认按钮 | Validator/前端/estimate 链路 + composer estimatePoints 增量 | 变更批 2026-08-21 | CLOSED（D19 全落地：流量全类型默认 4.0（零点实测可设）；零点只读 0 浓度禁用框；多点/准确度浓度点表（CO 后端统一 ppm，0/5/10/20/30/40 实测）；时长步重置按钮（回默认+预估复位实测）；composer estimatePoints +68 行增量） |
| G-BUG-17 | （用户 review：不合格任务没报告）报告生成 1 条/存储 0 条——QcmReportServiceImpl.saveBatch 只盖 createTime 不盖 updateTime，insertBatch 全列写入违 NOT NULL（同 G-BUG-14 模式） | saveBatch 双时间戳 + 回归锁测试 | 变更批2 2026-08-21 | CLOSED（修复后 job 15 重触发：报告落库 id=2，content 含 span_calibration_result=不合格 + 跨度数据全量；报告页列表可见） |
| G-BUG-18 | （同批发现）质控结果预览/导出对「执行成功但质控未通过」记录 500 拒绝——Objects.equals(Integer 状态, Long 枚举code) 恒 false（T1.4 评审预告的 Integer/Long 根因首次真实咬人） | 两处改 intValue 比较 | 变更批2 2026-08-21 | CLOSED（修后 preview 200；弹窗完整渲染：不合格徽章+报告全文+阶段时间线） |
| G-BUG-19 | （用户 review：报告里「设备恢复」无信息）composer 正常完成路径从不激活 recovery 阶段时间线——goToRecoveryPhase 只在 stop() 路径调用，恢复实际执行（日志 6/6 步）但 PhaseInfo 无 start/end → 报告时间线缺恢复段 | execute() 恢复链前补激活（防重入：已处于 recovery 不重置 start） | 变更批4 2026-08-21 | CLOSED（短时 E2E 实证：设备恢复 21:38:02~17 实际 15s 入时间线与报告预览；「自动校准按需跳过」如实展示为未执行；composer 144 绿） |
| G-BUG-20 | （用户 review：报告 SO₂浓度 1047.472 ppb 异常大）快照主浓度取值用裸 getDisplayValue() 返回 native µg/m³ 值（airdevice 国标口径，400ppb×2.619=1047.47），标签却无条件补 ppb → 数值与单位错位（判定链路 getDisplayValue(PPB) 一直正确，仅快照错；四种气体主浓度全中招） | 主浓度行带目标单位取值（SO2/NO2/O3→PPB、CO→PPM 与 D19 规范一致），与判定同口径；TDD 3 用例 + E2E 脚本加快照断言（≈标气浓度±10%） | 变更批5 2026-08-22 | CLOSED（TDD 红→绿 239；E2E 14/14：快照 SO₂浓度=400.000 ppb 判定同口径 + 报告合格；云库 recovery 中断 1.2h 与代码无关） |

## 3. G-DEAD · 死代码目标

| # | 现状证据 | 目标态 | 归属 | 状态 |
|---|---|---|---|---|
| G-DEAD-1 | `EnvQualityControlCustomMapper` 空接口无 XML | 删除 | Task 1.6 / AC-E1 | CLOSED（T1.5 删，全仓引用归零） |
| G-DEAD-2 | PatrolRunSettings.vue(650行)/PatrolCycleSettings.vue(179行) 未注册 + `tw:` 前缀 | 删除（dist 引用核对） | Task 1.6 / AC-E1、AC-F3 | CLOSED（T1.6 删两 vue 829 行，dist 零引用实证，release 重建过） |
| G-DEAD-3 | Task 死字段（deviceRegistry/executor 等；2026-08-20 Task 0.4 排查新增证据：`EnvQualityControlTask.java:34` `parameterMap` 全模块零读写） | 删除 | Task 1.6 / AC-E1 | CLOSED（T1.6 删 deviceRegistry/parameterMap/executor 死字段） |
| G-DEAD-4 | GenReportTask 无效 @Component + 遮蔽死字段 | 删除 | Task 1.6 / AC-E1 | CLOSED（T1.6 删 @Component 与遮蔽死字段） |
| G-DEAD-5 | 注释掉的代码块 / 双份 javadoc（Task:66-67/291-292、ReportController:17-28、ReportGenerator:763） | 全清 | Task 1.6 / AC-E1 | CLOSED（T1.6 注释代码块/双份 javadoc 清零） |
| G-DEAD-6 | System.out.println / printStackTrace（Integration:26-72 等 6 处） | log 统一 | Task 1.6 / AC-E1 | CLOSED（T1.6 System.out/printStackTrace 归零换 log，grep 实证） |
| G-DEAD-7 | service 两个死重载 `selectEnvQualityControlRecordsByTypeTime` | 删除 | Task 1.6 / AC-E1 | CLOSED（T1.6 删 2 个死重载，保留 4 参在用版） |

## 4. G-STRUCT · 结构目标

| # | 现状证据 | 目标态 | 归属 | 状态 |
|---|---|---|---|---|
| G-STRUCT-1 | 两 Task executeImpl ~220 行 × 2，70% 语句级重复（Task:286-505 / CustomTask:171-395） | 合并为 `QcmExecutionOrchestrator` 单编排模板；两 Task 薄化/删除 | Task 2.3 / AC-B3 | CLOSED（T2.3 两 Task 薄化为适配层，编排统一；QcResultFormatter 缝保留 constructResult） |
| G-STRUCT-2 | constructResult 177 行、ZERO/SPAN 分支 6 行重复（Task:81-258） | 分支合并；方法 ≤80 行 | Task 5.2 / AC-E4 | CLOSED（T5.2 A1：ZERO/SPAN 分支合并，方法<80 行，行为测试锁死） |
| G-STRUCT-3 | ReportGenerator.java 2113 行、7 内部类同模式；records/index.vue 2109 行 | 拆分：Java 单文件 ≤600 行、vue ≤800 行，行为由现有测试锁死 | Task 5.2 / AC-E4、AC-A9 | CLOSED（T5.2 A2/A3：ReportGenerator 2183→553 行+report/ 包 16 文件；records/index.vue 2110→768 行+4 组件+2 composable） |
| G-STRUCT-4 | controller 含业务（GasSetting.list 60+ 行、reportExport 手写流） | 下沉 service | Task 1.5 / 评审 | CLOSED（T1.5 GasSetting 下沉 + T5.1 导出分批 util（PagedExportSupport）；controller 仅留响应流写=ruoyi 导出惯例） |
| G-STRUCT-5 | service 直持 EcatCore 跨集成取 flow 执行 stop（RecordsServiceImpl:128-160） | 归编排器/终止域统一 | Task 2.3 / AC-B3 | CLOSED（T2.3 stop 归 service/编排终止域统一） |
| G-STRUCT-6 | `callExecuteImpl` 公开透传绕封装无调用方（CustomTask:165-167） | 删除 | Task 1.6 / AC-E1 | CLOSED（T2.3 删 callExecuteImpl） |

## 5. G-STD · 规范目标

| # | 现状证据 | 目标态 | 归属 | 状态 |
|---|---|---|---|---|
| G-STD-1 | pom 冗余 junit4 依赖（pom.xml:93-95） | 移除，统一 JUnit5 | Task 5.2 / AC-E11 | CLOSED（T5.2 B4：junit4 依赖删除） |
| G-STD-2 | Records 实体 253 行手写 getter（@Data 缺失） | Lombok 100% | Task 1.2 / AC-E3 | CLOSED（T1.2 三实体 @Data，253 行手写 getter 消失） |
| G-STD-3 | 通配 import + import 乱序（两 Task、RecordsController） | 清零 | Task 1.4/1.5 / AC-E7 | CLOSED（T5.2 B5：通配 import 归零） |
| G-STD-4 | 魔法值：`"admin"`/状态 `3`/`"calibration_check"` 两套类型标识/40000f/4.0×2 | 枚举/常量单源 | Task 5.2 / AC-E5 | CLOSED（T5.2 B6：FlowDefaults 常量类+LEGACY_CALIBRATION_CHECK_TYPE 合一+文案修正） |
| G-STD-5 | `STOPING` 拼写错误贯穿 DB/前端 | STOPPING 全链路（新表无历史包袱） | Task 1.4 / 门禁1 | CLOSED（T1.4 STOPPING 改名，vue 实证零波及） |
| G-STD-6 | javadoc 未闭合标签 + 中英混杂注释 | 规范化 | Task 5.2 / AC-E10 | CLOSED（T5.2 B8：javadoc 标签/中英混杂/注释引用 docs 路径归零） |
| G-STD-7 | SimpleDateFormat 实例字段非线程安全（GenReportTask:44、ReportGenerator:919/1888） | DateTimeFormatter | Task 5.2 / AC-E8 | CLOSED（T5.2 B7+终验补：SimpleDateFormat 全清→DateTimeFormatter） |

## 6. G-PERF · 性能目标

| # | 现状证据 | 目标态 | 归属 | 状态 |
|---|---|---|---|---|
| G-PERF-1 | 导出全量载内存 + report_content 大列 + 列表逐行 JSON 反序列化（两 export + findPage:36-49） | 列表去大列、导出分批流式 | Task 5.1 / AC-C6/C7 | CLOSED（T5.1：列表去 report_content 大列+导出 500/页分批+页内转轻量 VO） |
| G-PERF-2 | GasSetting 内存组装后 startPage() ThreadLocal 泄漏（:107-109） | 删除 + 防复发说明 | Task 0.3 / 门禁0 | CLOSED（Task 0.3：单行删除；依赖分析确认无 PageInfo 依赖；Report/Records 的 startPage 在 MyBatis 查询前属合法用法保留） |
| G-PERF-3 | 报告生成 N+1（generate 全量 + 8 处循环内设备查询） | 参数级预取缓存 | Task 5.1 / AC-C8 | CLOSED（T5.1：设备查询预取，计数桩实证 5 次→2 次=参数数） |
| G-PERF-4 | 报告逐条 insert | insertBatch | Task 1.4 / AC-C7 | CLOSED（T1.4 insertBatch） |
| G-PERF-5 | 模块无 DDL/索引脚本，LIKE 前导通配 | 三新表 DDL+索引随换代落地；LIKE 模式复核 | Task 1.1 / AC-C2 | CLOSED（T1.1 三新表 DDL+5 索引落库实证） |
| G-PERF-6 | stop 先取整行（含大字段）只为判存在 | update-where | Task 2.3 / 评审 | CLOSED（T2.3 selectStopTargetById 三列轻量查询） |

## 7. G-VUE · 前端目标（含 D17 基线核对）

| # | 现状证据 | 目标态 | 归属 | 状态 |
|---|---|---|---|---|
| G-VUE-1 | records/index.vue 2109 行（列表+7 预览+导出混杂） | 拆子组件/composable，≤800 行 | Task 5.2 / AC-E4 | CLOSED（T5.2 A3：records/index.vue 768 行） |
| G-VUE-2 | PDF 导出两页重复实现、audit_span 状态轮询重复 | 抽公共 composable | Task 5.2 / AC-E4 | CLOSED（T5.2 A3：usePdfExport/useExecutionPolling composable 两页共用） |
| G-VUE-3 | 冗余局部注册 ElTimeline（宿主已全局） | 清零 | Task 5.2 / AC-F6 | CLOSED（T5.2 A3：冗余局部注册删除） |
| G-VUE-4 | （D17 差距核对）现有四页与新页时间选择/组件基线一致性 | 时间选择一律 el-time-picker / el-date-picker（自研时间控件 0）；新页 100% element-plus + `qc:` tailwind；**不引入新 UI 库、`tw:` 前缀 0**；旧页重绑不引入新库 | Task 2.5 / AC-F1/F2/F3、RED-9 | CLOSED（T2.5 新页 100% element-plus：el-steps/el-time-picker/el-date-picker/el-checkbox-group；package.json 零改动；tw: 0） |
| G-VUE-5 | （用户 review：报告页显示无意义演示行）report/index.vue 列表初始值=本地 mockTableData（仪器A/NO001/张三/李四 演示数据 3.7KB），首载先渲染假数据接口回来再覆盖；且 fetch 后前端二次 includes 过滤破坏分页计数（null 字段抛 TypeError 风险）+ console.log 残留 | 删 mock 块、初始空数组+加载态、过滤分页全信后端、v-loading、清调试日志 | 变更批3 2026-08-21 | CLOSED（重启后五页面默认行为系统检查：报告页首载即真实数据无演示行；查看弹窗完整（不合格徽章+全文+时间线）；记录/计划/人工核查/钢瓶气默认值与重置行为全部正确） |

## 8. G-REQ · 需求差距目标（现有代码 → 新能力缺口）

| # | 缺口 | 目标态 | 归属 | 状态 |
|---|---|---|---|---|
| G-REQ-1 | 任务定义/调度在 ruoyi sys_job+Quartz，cron 0 点全暂停，入口割裂 | qcm 自建调度器+计划页+人性化配置（D1/G1） | Phase 2 全部 | CLOSED（T2.1-2.5：调度器+计划页+人性化配置全链路上线，浏览器实测） |
| G-REQ-2 | 无计划生命周期（启停/恢复/删除/立即执行） | 全生命周期 + 状态机（D3/G2） | Task 2.2/2.4 | CLOSED（T2.4 状态机 + T2.5 操作显隐：启停/恢复/删除/立即执行/编辑实测过） |
| G-REQ-3 | 无对外触发通道 | 进程内 SDK：trigger/queryExecution/getRecordDetail 报告级全量字段（触发/快照/时间线/监测/标准/计算/评定，外部仅凭 SDK 可生成报告，D8/D15/G3、FR-03-14/15） | Task 3.1/3.2 / AC-A7 | CLOSED（T3.1/3.2：api 零依赖包 5 文件+Impl，D15 报告级字段全量，204→217 测试链路绿，评审 APPROVED；获取=registry.getIntegration(...).getQualityControlSdk()） |
| G-REQ-4 | 记录无触发溯源（谁/哪里/批次/结构化失败原因） | trigger_user/batch_id/failure_reason/record_snapshot 全落（G4/D10） | Task 1.1-1.4/2.3 | CLOSED（T2.3：trigger_user/batch_id/failure_reason/record_snapshot 全落库并页面可见） |
| G-REQ-5 | 三表旧模型（含 plan 空壳、timestamp 无时区） | qcm_ 三新表 + timestamptz 统一；**重绑的现有功能（records/report 页面、报告生成）时间字段一并统一 Instant + TypeHandler，模块内不允许 timestamp/timestamptz 两套并存**（D13/D14/G5、FR-04-03） | Task 1.1-1.4 / AC-E8、门禁1 | CLOSED（T1.1-1.4 三表换代+Instant/timestamptz 全链路，D18 有效期两列恢复） |
| G-REQ-6 | 无多仪器零点类型 | qcm 侧契约 + NOT_READY 中间态（D4/D5） | Phase 4 | CLOSED（T4.1：枚举+矩阵校验+NOT_READY 闸前拒绝留痕+前端多选/标签/预估占位，评审 APPROVED；composer flow 接线为既定 OUT 独立任务） |
| G-REQ-7 | 时长参数无用户可配面 | 底层参数编辑+预估同源展示（D7） | Task 2.5 | CLOSED（T2.4 estimate 同源 + T2.5 第五步预填+实时预估实测：跨度检查 23分20秒/总 29分01秒） |
| G-REQ-8 | 种子散落 ruoyi（21 job+三表 DDL） | 旧处置+18 条等价 seed+模块内 DDL 真相源（D2） | Task 1.1/1.7 | CLOSED（T1.1 seed 18 条落库+T1.7 ruoyi 种子清理；44/45 误删已恢复） |

---

## 状态汇总

域小结：G-SEC 6 / G-BUG 10 / G-DEAD 7 / G-STRUCT 6 / G-STD 7 / G-PERF 6 / G-VUE 4 / G-REQ 8 = **54 项目标**，全部 OPEN（2026-08-20 基线，Task 0.0 复核冻结：D14/D15/D17 后补决策已并入 G-REQ-5/G-REQ-3/G-VUE-4 表述，无新增编号；D16 优秀级经审计项全量对照（计划尾表 S/C/D/R/N/P/V ↔ G 项）确认全覆盖，由 AC-A0/AC-E 域门禁兜底）。
门禁时在本节追加：`| Phase | CLOSED | WAIVED(理由) | 新增条目 |`。

| Phase | CLOSED | WAIVED(理由) | 新增条目 |
|---|---|---|---|
| Phase 0（2026-08-20） | G-SEC-1、G-SEC-2、G-PERF-2、G-BUG-1（4 项，证据见各条） | 0 | 0（Task 0.4 排查发现的 parameterMap 死字段并入 G-DEAD-3 证据，不新增编号） |
| Phase 1（2026-08-20） | 20 项：G-SEC-3/5/6、G-BUG-3/5/7/11、G-DEAD-1..7、G-PERF-4/5、G-REQ-5/8、G-STD-2/5（证据见各条；累计 CLOSED 24/55） | 0 | 1（G-BUG-11：T1.8 发现动态注册别名只认 BaseEntity 子类→整集成 404，FQCN 修复） |
| Phase 2（2026-08-21） | 12 项：G-REQ-1/2/4/7、G-STRUCT-1/5/6、G-BUG-2/4/10/12/13/14、G-PERF-6、G-VUE-4（浏览器全链路实测：创建/启停/立即执行/冲突/终止收敛；累计 CLOSED 40/58） | 0 | 3（G-BUG-12/13/14：T2.7 浏览器回归抓到的运行时缺陷，全部当轮修复+守卫） |
| Phase 3+4（2026-08-21） | 0（G-REQ-3/6 由 SDK/新类型任务直接实现无对应旧目标行；SDK 评审 APPROVED、T4.1 评审 APPROVED） | 0 | 0 |
| Phase 5+终验（2026-08-21） | 17 项：G-SEC-4、G-BUG-6/8/9、G-STRUCT-2/3/4、G-STD-1/3/4/6/7、G-PERF-1/3、G-VUE-1/2/3（**终态：58/58 全 CLOSED，0 WAIVED**） | 0 | 0（C6 报告列表缺索引为 EXPLAIN 终检发现，当轮补 DDL+库索引） |
| 变更批（2026-08-21 用户 review） | 3 项：G-BUG-15/16、G-REQ-9（TDD 红→绿 + 浏览器四场景实证；qcm 235 / composer 144 绿） | 0 | 3（用户四项反馈） |
| 变更批2（2026-08-21 用户 review） | 2 项：G-BUG-17/18（不合格报告链路两处断裂修复；qcm 236 绿；报告落库+预览全链路实证） | 0 | 2（报告不落库/预览误拒） |
| 变更批3（2026-08-21 用户 review） | 1 项：G-VUE-5（报告页 mock 演示行移除+二次过滤清理；五页面默认行为系统检查全过） | 0 | 1（报告页演示占位数据） |
| 变更批4（2026-08-21 用户 review） | 1 项：G-BUG-19（recovery 阶段时间线激活；短时 E2E 全阶段完整实证） | 0 | 1（恢复阶段时间线缺失） |
| 变更批5（2026-08-22 用户 review） | 1 项：G-BUG-20（快照单位口径；TDD+E2E 实证 400.000 ppb；**终态 66/66 CLOSED**） | 0 | 1（快照 µg/m³ 值错标 ppb） |
| 变更批7：钢瓶档案归一 airstation（2026-08-24，用户方案 A 定案） | 3 项：G-REQ-13（废 qcm_gas_info，溯源三要素真相源=airstation standard_gas 槽档案属性 gas_source/cylinder_id/gas_concentration；冻结读 AttrState 一次；+gas_concentration_unit 列；NO2→nox/O3 如实空）、G-REQ-14（StandardGas 元数据补 gas_source standalone 文本属性，persistable+默认「未设置」）、G-BUG-23（档案读取 getState()=null NPE 炸失败留痕——stateOf 统一判空+编排器日志补栈，TDD+record31/33 实证）；logicdevice +1 常量 / logicdevice-airstation 909 绿 / qcm 278 绿；遗留：standalone 属性持久化断点（bugs/bug-record-20260824-001500 待分析） | 0 | 3（含 G-BUG-23 当场发现当场修） |
| 变更批6：SDK 数据面强类型重构（2026-08-23，07-SDK 文档 v2.4 自主批） | 6 项：G-BUG-21（标气词汇断裂：writer 按数字代码查 qcm_gas_info 恒空→经 ParameterEnum 名称翻译对齐，TDD 锁定）、G-BUG-22（溯源弹窗保存抹浓度：整行覆盖契约下前端漏带非编辑字段→从缓存行原样回带，浏览器实测 400/ppb 保留）、G-REQ-10（SdkExecutionResult 强类型读面：38 列主表+3 子表完成时冻结、读时零关联；queryResults(ResultFilter)+getExecutionResult；执行身份链 trigger_request_id/flow_type/flow_execution_ref）、G-REQ-11（标气溯源配置面 GasSetting 逐气体 source/no+浓度，执行时快照+换瓶隔离实证 record16=060522/17=860823）、G-REQ-12（仪器识别+满量程完成时冻结 instrument_name/no/full_scale，CO=50ppm=50000ppb 归一）、G-STD-8（full_scale 与 Gen 零跨 FULL_SPAN 同源常量落列，非魔法值）；qcm 270 测试绿+全 reactor install 绿+短时 E2E×2+DB 冻结局+浏览器 GasSetting 回归 | 0 | 5（含 G-BUG-21/22 两缺陷，当场发现当场修） |
