import request from '@/utils/request'

// 查询质控记录列表
export function listRecords(query) {
  return request({
    url: '/quality_control/records/list',
    method: 'get',
    params: query
  })
}

// 质控记录执行阶段（运行中实时 + 已结束持久化）
export function getExecutionPhases(id) {
  return request({
    url: '/quality_control/records/' + id + '/execution_phases',
    method: 'get'
  })
}

// 下载质控结果 JSON（与 report_preview 同源）
export function exportReportJson(id) {
  return request({
    url: '/quality_control/records/' + id + '/report_export',
    method: 'get',
    responseType: 'blob'
  })
}

// 查询质控记录详细
export function getRecords(id) {
  return request({
    url: '/quality_control/records/' + id,
    method: 'get'
  })
}

// 质控结果预览（与报表生成同源）
export function getReportPreview(id) {
  return request({
    url: '/quality_control/records/' + id + '/report_preview',
    method: 'get'
  })
}

// 新增质控记录
export function addRecords(data) {
  return request({
    url: '/quality_control/records',
    method: 'post',
    data: data
  })
}

// 修改质控记录
export function updateRecords(data) {
  return request({
    url: '/quality_control/records',
    method: 'put',
    data: data
  })
}
// 中止质控记录
export function stopRecords(data) {
  return request({
    url: '/quality_control/records/stop',
    method: 'put',
    data: data
  })
}

// 删除质控记录
export function delRecords(id) {
  return request({
    url: '/quality_control/records/' + id,
    method: 'delete'
  })
}
