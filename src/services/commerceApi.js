import { get, patch, post } from '../utils/request.js'

export const commerceApi = {
  catalog: params => get('/api/public/products', Object.fromEntries(Object.entries(params || {}).filter(([, value]) => value != null && value !== ''))),
  allianceStores: params => get('/api/public/alliance-stores', params),
  publicStore: storeId => get(`/api/public/stores/${storeId}`),
  publicProducts: storeId => get(`/api/public/stores/${storeId}/products`),
  publicImageUrl: assetId => get(`/api/public/assets/${assetId}/download-url`),
  placeOrder: values => post('/api/public/orders', values),
  publicOrder: token => get(`/api/public/orders/${encodeURIComponent(token)}`),
  alipay: token => post(`/api/public/orders/${encodeURIComponent(token)}/alipay`, {}),
  refreshOrder: token => post(`/api/public/orders/${encodeURIComponent(token)}/refresh`, {}),
  merchantOrders: storeId => get(`/api/stores/${storeId}/orders`),
  updateOrder: (storeId, orderId, status, shipping = {}) => patch(`/api/stores/${storeId}/orders/${orderId}`, { status, ...shipping }),
}

export const orderStatuses = {
  PENDING_PAYMENT: '待支付',
  PENDING_CONFIRMATION: '待商家确认',
  CONFIRMED: '商家已确认',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}
