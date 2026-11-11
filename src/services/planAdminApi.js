import { get, post } from '../utils/request.js'

/** 平台运营管理会员套餐（需要计费运营权限）。套餐按版本管理：已建版本不可修改，改价格或权益就新建一个版本。 */
export const planAdminApi = {
  plans: () => get('/api/billing/operations/plans'),
  create: values => post('/api/billing/operations/plans', values),
  publish: (id, published, replacePublished = false) => post(`/api/billing/operations/plans/${encodeURIComponent(id)}/publication`, { published, replacePublished }),
}

/** 可以生成激活码的套餐：每个套餐标识取最新的版本，上没上架都算（站内不卖的套餐不上架，只发激活码）。规则同后端 activationPlans。 */
export function activationPlans(listings) {
  const latest = new Map()
  for (const { plan } of listings || []) {
    if (!latest.has(plan.code) || latest.get(plan.code).id < plan.id) latest.set(plan.code, plan)
  }
  return [...latest.values()]
}
