import request from '@/utils/request'

// 查询质控记录列表
export function listRecords(query) {
  return request({
    url: '/quality_control/records/list',
    method: 'get',
    params: query
  })
}

// 查询质控记录详细
export function getRecords(id) {
  return request({
    url: '/quality_control/records/' + id,
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
