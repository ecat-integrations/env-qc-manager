import request from '@/utils/request'

// 查询质控任务计划列表（status/qcType/planName 筛选，出参含调度摘要/next/last）
export function listPlan(query) {
  return request({
    url: '/quality_control/plan/list',
    method: 'get',
    params: query
  })
}

// 查询质控任务计划详细（含调度摘要与 next_fire_time）
export function getPlan(id) {
  return request({
    url: '/quality_control/plan/' + id,
    method: 'get'
  })
}

// 新增质控任务计划
export function addPlan(data) {
  return request({
    url: '/quality_control/plan',
    method: 'post',
    data: data
  })
}

// 修改质控任务计划（整行覆盖后重算 next）
export function updatePlan(data) {
  return request({
    url: '/quality_control/plan',
    method: 'put',
    data: data
  })
}

// 启用 / 暂停计划（action = enable | pause）
export function changePlanStatus(id, action) {
  return request({
    url: '/quality_control/plan/status/' + id + '/' + action,
    method: 'put'
  })
}

// 删除质控任务计划
export function delPlan(ids) {
  return request({
    url: '/quality_control/plan/' + ids,
    method: 'delete'
  })
}

// 立即执行（返回 BatchResult：accepted/batchId/reason）
export function runPlan(id) {
  return request({
    url: '/quality_control/plan/run/' + id,
    method: 'post'
  })
}

// 阶段预估（只读，不落库不触发）
export function estimatePlan(data) {
  return request({
    url: '/quality_control/plan/estimate',
    method: 'post',
    data: data
  })
}
