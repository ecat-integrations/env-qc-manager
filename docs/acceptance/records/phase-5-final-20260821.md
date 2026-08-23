# 验收记录 · Phase 5 + 终验（质量收尾）· 2026-08-21

执行：T5.1/T5.2 subagent（含评审）；终验控制器执行。**全程未 commit**（RED-7 ✓）。

## Phase 5 任务

| 项 | 结果 | 证据 |
|---|---|---|
| T5.1 报告性能 | PASS | 列表 SQL 去 report_content 大列（reportDisplayType 实证恒空串→改 report_type 映射）；导出 500/页分批+页内转轻量 VO+PageHelper 清 ThreadLocal；设备查询预取计数桩 **5→2 次=参数数**；7 个 Gen*ReportTest 断言零改动；226/226 |
| T5.2 结构与规范 | PASS | constructResult 分支合并；ReportGenerator **2183→553 行**（report/ 包 16 文件+模板方法）；records/index.vue **2110→768 行**（4 组件+2 composable 两页共用）；junit4 依赖删/通配 import 归零/魔法值收口 FlowDefaults/SimpleDateFormat 全清/javadoc 修缮/三 Task 惰性字段删/单站模型 javadoc 留档；静态扫全绿 |
| 终验补 G-BUG-6 | PASS | ReportGenerator 7 处 `catch(Throwable ignored)` → `Exception`+logger.debug（Error 不再吞）；grep ignored 归零 |

## 终验（AC 清单执行）

| AC | 结果 | 证据 |
|---|---|---|
| AC-B5 全 reactor | PASS | 修复 G-BUG-6 后可信轮次 `mvnd clean install -T 1C` **BUILD SUCCESS**（含 qcm 226 测试 + hj212×2 + composer 138 + 全部 90+ 模块） |
| AC-C1..C3/C6 EXPLAIN | PASS | 记录列表 create_time DESC Index/批次/plan_id/(task_type,execution_status)/调度器扫描全部索引路径；C6 报告列表初检 SeqScan → **补 idx_qcm_report_update_time**（DDL+seed+部署库同步）→ Index Scan |
| AC-A12/A13 回归 | PASS | 重启后四旧页（records 拆分后含行渲染/report 去大列/custom/gas）+ 计划页全绿；「多仪器零点质控」标签（ignoreCache 后确认）、向导多仪器多选+提示、console 0 error |
| 测试总量 | PASS | qcm 49（重构前）→ **226**；新增守卫：SQL 注入扫/insert 对称/SDK 零依赖/调度语义 17 用例/并发双触发与热循环防护 |
| 目标清单终态 | PASS | **58/58 全 CLOSED，0 WAIVED，新增 4（G-BUG-12/13/14 + C6 索引）全部当轮修复** |
| RED-1..9 | PASS | 鉴权全量/@PreAuthorize 恢复/`${}` 零/吞异常零/无 sleep/无 skip/未 commit/无降级猜测/无新 UI 库 |

## 遗留（透明声明，均为既定 OUT 或后续独立任务）

1. **composer multi_zero_check flow 接线**（qcm 侧契约就绪：类型/校验/记录/NOT_READY 留痕/seed #1；接线后零 qcm 改动即可运行）。
2. **报告生成器对新零点类型的适配**（依赖 1）。
3. **SDK HTTP 门面**（跨进程调用方出现时按需；api 契约即接口）。
4. **排队执行**（allowQueue 预留，传 true 明确 QUEUE_NOT_SUPPORTED）。
5. 调度到点（SCHEDULED 源）运行时观察：语义由虚拟时钟单测锁死；建议部署后启用一条 seed 计划观察首个真实到点（执行时长 ~29 分钟，属正常运行）。
6. 旧表 env_quality_control_*/旧 sys_job 21-41：按用户指令保留不动（互不妨碍）；qcm_auth.sql 幂等重跑 ×2 留运维执行。

执行人：subagent impl-5-1/5-2 + rev 系列；控制器终验；用户确认：____（自主模式授权，2026-08-20「后面任务你就自己自主执行」）
