// AI 创作站上，没用激活码开通会员的账号只能进「套餐与权益」和「设置」，其他模块显示锁定页。
// 这里只决定页面显不显示；写操作是否放行仍以后端为准（9001）。
export const MEMBER_FREE_ROUTES = new Set(['billing', 'settings', 'forbidden', 'not-found'])

/**
 * @returns {'' | 'loading' | 'locked'} 空字符串表示正常显示页面。
 * 账户读不到（error）时不拦，避免服务波动把所有页面锁住；主域名不展示 AI 创作，也不拦。
 */
export function memberGateState({ authenticated, aiCreationHidden, routeName, summary, error }) {
  if (!authenticated || aiCreationHidden || MEMBER_FREE_ROUTES.has(routeName) || error) return ''
  if (!summary) return 'loading'
  return summary.memberActive ? '' : 'locked'
}
