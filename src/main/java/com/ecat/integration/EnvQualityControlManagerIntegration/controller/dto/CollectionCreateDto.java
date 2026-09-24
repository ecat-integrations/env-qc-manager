package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 集合快捷方式创建请求体（03 设计 §6 工厂）：日常/周核查向导一次提交，
 * 工厂展开为「同时零点 + 逐气跨度」原子 plan 行集合，行内默认值（槽位时刻、
 * 优先级、跨度浓度）由工厂填充，本 DTO 只收模板选择与稀疏覆盖。
 *
 * @author coffee
 */
@Data
public class CollectionCreateDto {

    /** 模板：DAILY / WEEKLY——仅决定行默认优先级（DAILY→LOW、WEEKLY→HIGH）与前端命名提示，与调度校验彻底解耦 */
    private String template;

    /** 集合名（非空 ≤100，作为每行计划名前缀：{集合名}-{气种段}-{零点|跨度}） */
    private String collectionName;

    /** 勾选气种（SO2/NO2/CO/O3 非空子集；四气全勾零点行气种段为「四气」） */
    private List<String> instruments;

    /** 调度类型：INTERVAL / WEEKLY / MONTHLY（集合不再产出 DAILY，按天语义走 INTERVAL；ONCE 被显式拒绝——共享气路套件须逐行错峰，单时刻无法承载；日常集合也能配任意类型——快捷方式是预填不是限制） */
    private String scheduleType;

    /** INTERVAL：间隔天数 1-31（服务端不猜默认，缺省即拒） */
    private Integer intervalDays;

    /** WEEKLY：隔周数 1-52（空=每 1 周；>1 时须提供 planStartTime） */
    private Integer intervalWeeks;

    /** WEEKLY：星期几（1=周一..7=周日） */
    private List<Integer> weekdays;

    /** MONTHLY：每月几号（1-31；当月无该日跳过该月） */
    private List<Integer> monthDays;

    /** 有效期起（ISO-8601；INTERVAL 必填——锚点日由其派生；隔周>1 必填） */
    private String planStartTime;

    /** 有效期止（ISO-8601，可空） */
    private String planEndTime;

    /** 校准策略：STANDARD / CALIBRATE_LOW_DRIFT；空 = STANDARD（落 NULL），逐行同值预填 */
    private String calibrationPolicy;

    /** 间隔预警强制保存：预警非阻断，true 时携 warnings 照常落库 */
    private Boolean force;

    /** 逐行稀疏覆盖（可空；rowKey 定位行，仅覆盖非空字段，值域由行级校验器兜） */
    private List<RowOverride> rows;

    /** 行覆盖项：零点行 rowKey="zero"，跨度行 rowKey=气种代码键（须已勾选）。 */
    @Data
    public static class RowOverride {

        private String rowKey;

        /** 时（0-23） */
        private Integer hour;

        /** 分（0-59） */
        private Integer minute;

        /** 同日优先级：NONE / LOW / HIGH */
        private String sameDayPriority;

        /** 阶段时长覆盖（key 白名单与单行创建同源，零点行为其子集） */
        private Map<String, Object> durationOverrides;
    }
}
