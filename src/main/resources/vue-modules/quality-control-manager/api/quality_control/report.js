// report.js - 用于环境质量控制报表相关的 API 请求

import request from '@/utils/request';

/**
 * 查询环境质量控制报表列表
 * @param {Object} query - 查询参数（如 reportName, reportType 等）
 * @returns {Promise}
 */
export function listReport(query) {
  return request({
    url: '/quality_control/report/list',
    method: 'get',
    params: query
  });
}

/**
 * 导出环境质量控制报表（Excel 或 PDF）
 * @param {Object} data - 报表查询数据
 * @param {string} format - 导出格式（xlsx/pdf）
 * @returns {Promise}
 */
export function exportReport(data, format = 'xlsx') {
  return request({
    url: `/quality_control/report/export`,
    method: 'post',
    data: data,
    responseType: 'blob' // 必须设置为 blob 以便下载文件
  });
}

/**
 * 获取环境质量控制报表详细信息
 * @param {Number} id - 报表ID
 * @returns {Promise}
 */
export function getReportDetail(id) {
  return request({
    url: `/quality_control/report/${id}`,
    method: 'get'
  });
}

/**
 * 新增环境质量控制报表
 * @param {Object} data - 报表数据
 * @returns {Promise}
 */
export function addReport(data) {
  return request({
    url: '/quality_control/report',
    method: 'post',
    data: data
  });
}

/**
 * 修改环境质量控制报表
 * @param {Object} data - 报表数据
 * @returns {Promise}
 */
export function updateReport(data) {
  return request({
    url: '/quality_control/report',
    method: 'put',
    data: data
  });
}

/**
 * 删除环境质量控制报表
 * @param {Array<Number>} ids - 要删除的报表ID数组
 * @returns {Promise}
 */
export function deleteReport(ids) {
  return request({
    url: `/quality_control/report/${ids.join(',')}`,
    method: 'delete'
  });
}

/**
 * 批量导出环境质量控制报表
 * @param {Array<Number>} ids - 报表ID数组
 * @param {string} format - 导出格式（xlsx/pdf）
 * @returns {Promise}
 */
export function batchExportReports(ids, format = 'xlsx') {
  return request({
    url: `/quality_control/report/export`,
    method: 'post',
    data: { ids },
    responseType: 'blob'
  });
}
