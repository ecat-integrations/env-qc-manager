package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 集合快捷方式创建回执：created=false 表示被间隔预警拦截（零落库，warnings 说明，
 * 前端确认后携 force 重提）；estimateFailures 是提示性清单——某行时长预估不可用
 * （如 multi 类型在 composer 接线前）只跳过该行预警，不阻断建计划。
 *
 * @author coffee
 */
@Data
@RequiredArgsConstructor
public class CollectionCreateResult {

    /** true = 已落库；false = 被间隔预警拦截（未落库） */
    private final boolean created;

    /** 已落库的行（含 id；拦截时为空表） */
    private final List<QcmPlan> plans;

    /** 间隔预警清单（force 落库时也随行返回供前端提示） */
    private final List<String> warnings;

    /** 预估不可用的提示清单（不拦截） */
    private final List<String> estimateFailures;
}
