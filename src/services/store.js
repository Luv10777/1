/**
 * 门店服务
 */

import { get, post, put, del } from '../utils/request'
import { isDemoMode } from '../utils/config'
import * as mockStore from './mockStore'

export const storeService = {
  /**
   * 获取门店列表
   */
  async list(merchantId, params) {
    if (isDemoMode()) {
      return mockStore.mockGetStoreList(merchantId, params)
    }
    return get(`/api/merchants/${merchantId}/stores`, params)
  },

  /**
   * 获取门店详情
   */
  async get(id) {
    if (isDemoMode()) {
      return mockStore.mockGetStore(id)
    }
    return get(`/api/stores/${id}`)
  },

  /**
   * 创建门店
   */
  async create(merchantId, data) {
    if (isDemoMode()) {
      return mockStore.mockCreateStore(merchantId, data)
    }
    return post(`/api/merchants/${merchantId}/stores`, data)
  },

  /**
   * 更新门店
   */
  async update(id, data) {
    if (isDemoMode()) {
      return mockStore.mockUpdateStore(id, data)
    }
    return put(`/api/stores/${id}`, data)
  },

  /**
   * 删除门店
   */
  async delete(id) {
    if (isDemoMode()) {
      return mockStore.mockDeleteStore(id)
    }
    return del(`/api/stores/${id}`)
  },

  /**
   * 启停门店
   */
  async toggleStatus(id, enabled) {
    if (isDemoMode()) {
      return mockStore.mockToggleStoreStatus(id, enabled)
    }
    return put(`/api/stores/${id}/status`, { enabled })
  }
}
