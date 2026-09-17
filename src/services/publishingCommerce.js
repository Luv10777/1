// Publishing accounts are currently local fixtures. Keep this boundary explicit
// until the backend provides authorized Douyin merchant and product responses.
const fixtures = {
  'dy-main': {
    authorized: true, enabled: true, merchantName: '炭火烧鸟',
    packages: [
      { id: 'dy-main-hubin-pair', accountId: 'dy-main', store: '炭火烧鸟·湖滨店', name: '双人烧鸟招牌套餐', original: '¥198', price: '¥129', commission: '12%' },
      { id: 'dy-main-hubin-skewers', accountId: 'dy-main', store: '炭火烧鸟·湖滨店', name: '招牌烧鸟 8 串', original: '¥88', price: '¥59', commission: '10%' },
    ],
  },
  'dy-food': { authorized: true, enabled: false, packages: [] },
}

export function commerceSnapshot(account, store, record) {
  const base = { packages: [], merchantName: record?.merchantName || '', source: 'demo' }
  if (!account || account.platform !== '抖音') return { ...base, state: 'no_account' }
  if (!record) return { ...base, state: 'not_connected' }
  if (!record.authorized) return { ...base, state: 'expired' }
  if (record.error) return { ...base, state: 'error' }
  if (!record.enabled) return { ...base, state: 'not_enabled' }
  const packages = (record.packages || []).filter(item => item.accountId === account.id && item.store === store)
  return { ...base, state: packages.length ? 'ready' : 'empty', packages }
}

export async function loadAccountCommerce(account, store) {
  return commerceSnapshot(account, store, fixtures[account?.id])
}
