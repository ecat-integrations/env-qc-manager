import request from '@/utils/request'

// 查询质控记录列表
export function listGasSetting(query) {
  return request({
    url: '/quality_control/gas_setting/list',
    method: 'get',
    params: query
  })
}

// 修改质控记录
export function updateGasSetting(data) {
  return request({
    url: '/quality_control/gas_setting',
    method: 'post',
    data: data
  })
}
