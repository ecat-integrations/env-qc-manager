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

// 质控记录「过程展示」解析（分析仪标识/浓度曲线通道/全属性实时快照/目标浓度线；tabs 批 C）
export function getLiveProcess(id) {
  return request({
    url: '/quality_control/records/' + id + '/live_process',
    method: 'get'
  })
}

// ADM 历史横表查询（已完成记录的回放面板数据源，批11 起替代 status-data：unit=custom 走
// HISTORY 偏好行换算——浓度参数得体积单位 ppb/ppm 与质控标准值同口径，状态参数无偏好行原生保底）。
// 走 ruoyi 同源统一 request 实例（自动带鉴权），需 adm:monitor:list 权限；
// query：granularity/start/end（上海墙钟 ISO 秒串）/params（uid:attrId 逗号串）/
// unit='custom'/pageNum（必传，缺省后端 500）/pageSize；响应 data={columns,rows,total,...}
export function getAdmHistory(query) {
  return request({
    url: '/adm-monitor/history',
    method: 'get',
    params: query
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
