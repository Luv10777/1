/**
 * 商家服务
 */

import { get, post, put, del } from '../utils/request'
import { isDemoMode } from '../utils/config'
import * as mockMerchant from './mockMerchant'

export const merchantService = {
  /**
   * 获取商家列表
   */
  async list(params) {
    if (isDemoMode()) {
      return mockMerchant.mockGetMerchantList(params)
    }
    return get('/api/merchants', params)
  },

  /**
   * 获取商家详情
   */
  async get(id) {
    if (isDemoMode()) {
      return mockMerchant.mockGetMerchant(id)
    }
    return get(`/api/merchants/${id}`)
  },

  /**
   * 创建商家
   */
  async create(data) {
    if (isDemoMode()) {
      return mockMerchant.mockCreateMerchant(data)
    }
    return post('/api/merchants', data)
  },

  /**
   * 更新商家
   */
  async update(id, data) {
    if (isDemoMode()) {
      return mockMerchant.mockUpdateMerchant(id, data)
    }
    return put(`/api/merchants/${id}`, data)
  },

  /**
   * 删除商家
   */
  async delete(id) {
    if (isDemoMode()) {
      return mockMerchant.mockDeleteMerchant(id)
    }
    return del(`/api/merchants/${id}`)
  },

  /**
   * 启停商家
   */
  async toggleStatus(id, enabled) {
    if (isDemoMode()) {
      return mockMerchant.mockToggleMerchantStatus(id, enabled)
    }
    return put(`/api/merchants/${id}/status`, { enabled })
  }
}
