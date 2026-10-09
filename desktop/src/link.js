// 网页版里的“打开桌面端”是一条 yifangzhi://live?store=<门店编号> 链接：那边正在配置哪家门店，就带哪家的编号。
// 这种链接任何网页都能发起，所以只认门店编号这一样东西，而且只认纯数字。
// 它只是个建议：软件里登录的账号看不到这家门店，或者正在直播，页面都会不理它。

const SCHEME = 'yifangzhi:'

/** 把一条链接读成 { storeId }。不是我们的链接返回 null；没带门店或带得不对时 storeId 为 null。 */
export function readLink(url) {
  let parsed
  try { parsed = new URL(String(url)) } catch { return null }
  if (parsed.protocol !== SCHEME) return null
  const store = parsed.searchParams.get('store') || ''
  return { storeId: /^[1-9]\d{0,14}$/.test(store) ? Number(store) : null }
}

/** Windows 上链接是作为启动参数送来的：从参数里把它找出来，没有就是空串。 */
export const linkInArguments = argv => argv.find(value => typeof value === 'string' && value.startsWith(`${SCHEME}//`)) || ''
