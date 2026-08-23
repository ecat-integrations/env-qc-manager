# 验收记录 · 变更批7：钢瓶档案归一 airstation（废 qcm_gas_info）· 2026-08-24

流程：用户指误（「钢瓶编号在 logic device station 的 3 个设备对象上，借鉴 asm 读取，别单独维护一套」）
→ 调研（asm snapshot 读取模式 / StandardGas 元数据 / live 实证写读回）→ 方案对比（A 归一 / B 同步 / C 混读，
用户定 A + 三子决策：gas_source 加属性默认「未设置」、NO2→nox、GasSetting 转写）→ 实施 → 验收。
**commit 授权：本批 3 个集成仓库（logicdevice / logicdevice-airstation / env-quality-control-manager），
根 workspace 不动。**

## 交付物

### 1. logicdevice（+1 常量）
`StandardGas.GAS_SOURCE = "gas_source"`。

### 2. logicdevice-airstation（档案元数据补齐）
- `StandardGasLogicDeviceMapping`：`gas_source` standalone 文本属性（changeable=true / mapable=false /
  **persistable=true / defaultValue=「未设置」**——与 cylinder_id 同机制同档案面）；
- i18n「标气来源」；属性计数守卫 19→20（×3 测试）+ gas_source 定义断言。

### 3. qcm（方案 A 主体）
- **CylinderArchiveSupport**（新）：槽映射（1→so2 / 2→**nox** / 4→co / 3(O3)→null 发生器供气）+
  `readArchive`（asm 同款 AttrState 单次读，无撕裂）+ `writeTrace`（setDisplayValue 同通道）；
  state=null 统一判空（G-BUG-23）；
- **ResultSnapshotWriter**：溯源四列（+`gas_concentration_unit` 新列）完成时读档案冻结；
- **GasSettingService/Controller/DTO**：/info GET=四行恒返回（读档案）/ PUT=换瓶登记（转写档案，
  O3 硬拒 400）；删 `QcmGasInfo` 实体+mapper+XML+DDL 表定义（部署库已 DROP）；
- **GasSetting.vue**：NO 卡片→NO2 档案键归一；保存 payload 只带来源/编号；
- **QcmExecutionOrchestrator**：异常日志补 full stack（G-BUG-23 排查中发现的日志缺陷）；
- DDL：`qcm_record` +`gas_concentration_unit`（39 列，部署库已 ALTER）。

## 过程缺陷（当场发现当场修 / 记录待分析）
- **G-BUG-23（已修）**：`getState()` 对从未写入的属性返回 null → 档案读取 NPE → **连锁炸失败留痕**
  （end_time/instrument 全空 + 无栈日志）。修：stateOf 统一判空 + 编排器日志带栈；TDD 回归
  `readArchive_stateNullAttributes_dontThrow`；record 31（失败留痕完整）+ record 33（成功 14/14）实证。
- **遗留待分析（bugs/bug-record-20260824-001500）**：standalone persistable 属性写入值**不跨重启**
  （MapDB db/wal 无键实证；`device.getCore()` null / initFromDefinition 未生效 / publicState 未调 三选一
  待定位）。换瓶登记当次生效、重启丢失。影响所有 standalone 持久化属性（manual_status 等待查）。
  **commit 后分析（用户指令）。**

## 验证

| 层 | 结果 |
|---|---|
| logicdevice-airstation 单测 | **909/909 绿**（计数守卫×3 更新+gas_source 定义断言；一个时机未到的链路测试删除——持久化断点定位后写针对性红测试） |
| qcm 单测 | **278/278 绿**（270→278：CylinderArchiveSupportTest 7 用例 + writer 溯源用例重写×3 + service 用例重写×4 + 守卫 39 列） |
| 全 reactor | `mvnd clean install -T 1C` BUILD SUCCESS |
| 转写链（live） | GasSetting PUT /info 登记 SO2 来源+编号 → **「保存成功」→ GET 读回 = 从 airstation 档案读出的登记值**（方案 A 核心链实证） |
| E2E | record 33 **14/14 PASS**（成功路径全链）；E2E「登记→执行→冻结」最后一环待环境绿窗（命令锁间歇饿死，与代码无关——record 31 失败留痕完整即证） |
| DB | qcm_record 39 列；qcm_gas_info 已 DROP；冻结列在 record 33 全绿（溯源列 null=档案未写入，语义正确） |

## 与变更批6 的关系
批6 建的 qcm_gas_info（含 G-BUG-21 词汇修复/G-BUG-22 回带修复）**生命周期一天**——用户指误后整体废弃，
溯源真相源上移到设备档案层。批6 的其余交付（SdkExecutionResult 强类型读面/身份链/子表/仪器冻结/满量程）
不受影响，全部保留。

执行人：主会话（用户逐点确认：方案 A + 三子决策 + commit 授权 + 顺序=acceptance→install 绿→commit）
