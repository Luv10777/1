import { get, patch, post } from '../utils/request.js'

export const commerceApi = {
  featuredListing: () => get('/shop/featured.json'),
  publicStore: storeId => get(`/api/public/stores/${storeId}`),
  publicProducts: storeId => get(`/api/public/stores/${storeId}/products`),
  publicImageUrl: assetId => get(`/api/public/assets/${assetId}/download-url`),
  placeOrder: values => post('/api/public/orders', values),
  publicOrder: token => get(`/api/public/orders/${encodeURIComponent(token)}`),
  merchantOrders: storeId => get(`/api/stores/${storeId}/orders`),
  updateOrder: (storeId, orderId, status, shipping = {}) => patch(`/api/stores/${storeId}/orders/${orderId}`, { status, ...shipping }),
}

export const orderStatuses = {
  PENDING_CONFIRMATION: '待商家确认',
  CONFIRMED: '商家已确认',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}
