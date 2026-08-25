/**
 * 品牌服务
 */

import { get, post, put, del } from '../utils/request'
import { isDemoMode } from '../utils/config'
import * as mockBrand from './mockBrand'

export const brandService = {
  /**
   * 获取品牌列表
   */
  async list(merchantId, params) {
    if (isDemoMode()) {
      return mockBrand.mockGetBrandList(merchantId, params)
    }
    return get(`/api/merchants/${merchantId}/brands`, params)
  },

  /**
   * 获取品牌详情
   */
  async get(id) {
    if (isDemoMode()) {
      return mockBrand.mockGetBrand(id)
    }
    return get(`/api/brands/${id}`)
  },

  /**
   * 创建品牌
   */
  async create(merchantId, data) {
    if (isDemoMode()) {
      return mockBrand.mockCreateBrand(merchantId, data)
    }
    return post(`/api/merchants/${merchantId}/brands`, data)
  },

  /**
   * 更新品牌
   */
  async update(id, data) {
    if (isDemoMode()) {
      return mockBrand.mockUpdateBrand(id, data)
    }
    return put(`/api/brands/${id}`, data)
  },

  /**
   * 删除品牌
   */
  async delete(id) {
    if (isDemoMode()) {
      return mockBrand.mockDeleteBrand(id)
    }
    return del(`/api/brands/${id}`)
  }
}
