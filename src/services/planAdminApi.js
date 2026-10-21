import { get, post } from '../utils/request.js'

/** 平台运营管理会员套餐（需要计费运营权限）。套餐按版本管理：已建版本不可修改，改价格或权益就新建一个版本。 */
export const planAdminApi = {
  plans: () => get('/api/billing/operations/plans'),
  create: values => post('/api/billing/operations/plans', values),
  publish: (id, published, replacePublished = false) => post(`/api/billing/operations/plans/${encodeURIComponent(id)}/publication`, { published, replacePublished }),
}
