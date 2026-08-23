-- ============================================================================
-- qcm_auth.sql — env-quality-control-manager「质控任务计划」菜单 + 按钮权限 seed
-- 依据：docs/requirements/05-权限与菜单.md（FR-05-04 / FR-05-07 ~ FR-05-09）。
-- 库：ruoyi 主库（PostgreSQL）public schema。app 不自动跑；运维手动 apply，幂等可重跑。
-- 执行顺序（新库全量）：ruoyi public.sql → qcm_data.sql → qcm_auth.sql。
-- 幂等设计：sys_menu 无唯一约束，全用 DO 块 + WHERE NOT EXISTS 守卫（按 menu_name / perms
--           自然键判存在），可重跑零报错、零重复行。
--
-- 【menu_id 段声明】qcm 固定占用 2200~2210（menu_id=2200 菜单 + 2201~2206 六个按钮）。
--   段位选择依据：部署库侦察（2026-08-20）max(menu_id)=2106；asm_auth.sql 用动态 max+1
--   不占固定段（其插入会取当时 max+1，落在 2107+ 顺延），qcm 固定段 2200+ 与其不冲突。
--   其他模块新增固定段 seed 请避开 2200~2210。
-- 菜单挂载：parent_id=2000（「数据管理」目录）——与现有「质控记录」菜单(menu_id=2064)
--   同父平级（FR-05-09 侦察结论：qcm 现有 4 页中仅质控记录有 sys_menu 行，其 parent=2000）。
-- ============================================================================

DO $$
DECLARE
    p text;
    mid bigint;
    menu bigint := 2200;   -- 「质控任务计划」菜单（C）
BEGIN
    -- 菜单项（C，可见；与 2064「质控记录」同挂 parent=2000）
    IF NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_name = '质控任务计划' AND menu_type = 'C') THEN
        INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache,
                             menu_type, visible, status, perms, icon, create_time, update_time, remark)
        VALUES (menu, '质控任务计划', 2000, 65, 'quality_control_plan', 'quality_control/quality_control_plan/index', '', 1, 0,
                'C', '0', '0', 'quality_control:plan:list', 'date', now(), now(),
                'qcm 质控任务计划页（调度计划 CRUD/启停/立即执行）；页面实体在 qcm vue-modules module-config.json');
    END IF;
    -- 授 admin 角色(role_id=1)
    IF NOT EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON rm.menu_id = m.menu_id
                   WHERE rm.role_id = 1 AND m.menu_name = '质控任务计划' AND m.menu_type = 'C') THEN
        INSERT INTO sys_role_menu(role_id, menu_id)
        SELECT 1, menu_id FROM sys_menu WHERE menu_name = '质控任务计划' AND menu_type = 'C';
    END IF;

    -- 6 个按钮权限（F），逐一对应计划管理 REST 端点 @PreAuthorize（FR-05-04）：
    --   list=列表查询 / query=详情+下次触发时间+调度摘要 / add=新增
    --   edit=编辑+启用暂停（生命周期开关归入 edit）/ remove=删除 / run=立即执行
    FOREACH p IN ARRAY ARRAY[
        'quality_control:plan:list',
        'quality_control:plan:query',
        'quality_control:plan:add',
        'quality_control:plan:edit',
        'quality_control:plan:remove',
        'quality_control:plan:run'
    ] LOOP
        IF NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = p) THEN
            mid := (SELECT coalesce(max(menu_id), 0) + 1 FROM sys_menu);
            -- 守卫：动态 max+1 万一撞进 2200 保留段，顺延跳过
            IF mid >= 2200 AND mid <= 2210 THEN mid := 2211; END IF;
            INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache,
                                 menu_type, visible, status, perms, icon, create_time, update_time, remark)
            VALUES (mid, p, menu, 0, '', NULL, '', 1, 0,
                    'F', '0', '0', p, '#', now(), now(),
                    'qcm 质控任务计划按钮权限(controller @PreAuthorize 对应)');
        END IF;
        -- 授 admin 角色(role_id=1)：admin 用户(user_id=1)持 *:*:* 通配本就全过，
        -- 此授权让非 user_id=1 的 admin 角色用户也获得该权限（asm_auth.sql 同惯例）
        IF NOT EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON rm.menu_id = m.menu_id
                       WHERE rm.role_id = 1 AND m.perms = p) THEN
            INSERT INTO sys_role_menu(role_id, menu_id)
            VALUES (1, (SELECT menu_id FROM sys_menu WHERE perms = p));
        END IF;
    END LOOP;
END $$;
