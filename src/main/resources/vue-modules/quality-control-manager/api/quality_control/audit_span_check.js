// report.js - 用于自定义质量控制相关的 API 请求

import request from '@/utils/request';

/**
 * 执行自定义跨度检查
 * @param {Object} queryData - 查询参数
 * @returns {Promise} - 返回自定义跨度检查是否成功的结果 True or False
 */
export function executeAuditSpanCheck(queryData) {
  return request({
    url: '/quality_control/custom/audit_span_check',
    method: 'post',
    data: queryData
  });
}

/**
 * 查询是否正在执行的质控任务
 * @returns {Promise} - 返回是否有正在执行的质控任务 True or False
 */
export function is_executor_free() {
  return request({
    url: `/quality_control/custom/is_executor_free`,
    method: 'get'
  });
}
