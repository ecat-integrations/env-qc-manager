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

// 标气溯源配置列表（§4.2：qcm_gas_info 逐气体一行）
export function getGasInfo() {
  return request({
    url: '/quality_control/gas_setting/info',
    method: 'get'
  })
}

// 逐气体保存标气溯源配置（来源/编号/浓度，整行覆盖）
export function saveGasInfo(data) {
  return request({
    url: '/quality_control/gas_setting/info',
    method: 'put',
    data: data
  })
}
