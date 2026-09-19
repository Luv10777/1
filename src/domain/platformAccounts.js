export const accountPlatforms = [
  { name: '美团', identity: ['商家门店'], provider: '美团商家服务授权' },
  { name: '大众点评', identity: ['商家门店'], provider: '大众点评商家服务授权' },
  { name: '抖音', identity: ['POI认领商家', '企业号', '达人号', '员工子账号'], provider: '抖音开放平台服务商授权' },
  { name: '小红书', identity: ['企业号', '达人号', '员工子账号'], provider: '小红书第三方平台接入' },
  { name: '视频号', identity: ['企业号', '主理人账号', '员工子账号'], provider: '微信公众平台 · 视频号授权' },
]
export function permissionOptions(platform) {
  if (['美团', '大众点评'].includes(platform)) return ['门店POI关联', '团购券挂载', '数据回流']
  return ['视频/图文发布', '门店POI关联', '团购券挂载', '数据回流']
}
export function remainingDays(account, now = Date.now()) {
  return Math.max(0, Math.ceil((new Date(account.expiresAt).getTime() - now) / 86400000))
}
export function authorizationStatus(account, now = Date.now()) {
  if (account.revoked || remainingDays(account, now) <= 0) return 'expired'
  return remainingDays(account, now) < 7 ? 'expiring' : 'active'
}
export function canPublishAccount(account, now = Date.now()) {
  return ['抖音', '小红书', '视频号'].includes(account.platform) && authorizationStatus(account, now) !== 'expired' && account.permissions.includes('视频/图文发布')
}
export function filterAccounts(accounts, platform, query) {
  const keyword = query.trim().toLocaleLowerCase()
  return accounts.filter(account => (platform === '全部' || account.platform === platform) && `${account.name} ${account.uid} ${account.type}`.toLocaleLowerCase().includes(keyword))
}
export function createMockAccounts(now = Date.now()) {
  const rows = [
    ['dy-main', '抖音', 'POI认领商家', '炭火烧鸟·湖滨店', '88720194', '2.8万', 45, '炭', 'bg-slate-800 text-white', '/images/publishing/restaurant.jpg'],
    ['dy-food', '抖音', '达人号', '杭州知味探店', '62910481', '12.6万', 32, '知', 'bg-amber-100 text-amber-800', ''],
    ['xhs-main', '小红书', '企业号', '西湖边觅食记', '30218477', '8.9万', 28, '觅', 'bg-rose-50 text-rose-600', ''],
    ['wx-main', '视频号', '企业号', '方志生活精选', '77409126', '4.2万', 5, '志', 'bg-emerald-50 text-emerald-700', ''],
    ['wx-store', '视频号', '主理人账号', '炭火烧鸟·主理人日记', '77409128', '1.2万', 60, '炭', 'bg-slate-100 text-slate-700', ''],
  ]
  return rows.map(([id, platform, type, name, uid, fans, days, avatar, avatarClass, image]) => ({
    id, platform, type, name, uid, fans, avatar, avatarClass, image, handle: uid,
    permissions: permissionOptions(platform), revoked: false,
    expiresAt: new Date(now + days * 86400000).toISOString(),
  }))
}
