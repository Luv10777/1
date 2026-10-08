// 商家填进来的东西 → 网页端直播间号（live.douyin.com/ 后面那一串）。
// 可以直接填直播间号或直播间网址，也可以粘贴抖音 App 里“分享 → 复制链接”的整段内容。

const ROOM_ID = /^[A-Za-z0-9_.-]{1,64}$/
const SHARE_LINK = /https?:\/\/v\.douyin\.com\/([A-Za-z0-9_-]{4,32})\/?/
// 短链只会跳到抖音自己的落地页；跳去别处就不跟。
const LANDING_HOST = /(^|\.)(douyin\.com|amemv\.com|iesdouyin\.com)$/
const WEB_RID = /\\?"webRid\\?"\s*:\s*\\?"([A-Za-z0-9_.-]{1,64})\\?"/
const MAX_HOPS = 5
const TIMEOUT = 10000
// 落地页只对手机浏览器返回带直播间信息的页面。
const MOBILE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'

/** 接受直播间号，或整条 live.douyin.com 网址。 */
export function normalizeRoomId(input) {
  const value = String(input ?? '').trim()
  const fromUrl = value.match(/live\.douyin\.com\/([A-Za-z0-9_.-]+)/)
  const id = fromUrl ? fromUrl[1] : value
  if (!ROOM_ID.test(id)) throw new Error('没有认出直播间：请粘贴抖音 App 里“分享 → 复制链接”得到的整段内容')
  return id
}

/** 复制出来的往往是一段话，短链夹在中间。取出短链本身；没有就返回 null。 */
export function extractShareLink(text) {
  const match = String(text ?? '').match(SHARE_LINK)
  return match ? `https://v.douyin.com/${match[1]}/` : null
}

/** 落地页里内嵌的直播间信息带有网页端直播间号（webRid）。 */
export function extractWebRid(html) {
  const match = String(html ?? '').match(WEB_RID)
  return match ? match[1] : null
}

/** 像手机浏览器那样打开分享短链，返回网页端直播间号。 */
export async function resolveShareLink(text, { fetch = globalThis.fetch } = {}) {
  let url = extractShareLink(text)
  if (!url) throw new Error('这不是抖音的分享链接')
  for (let hop = 0; hop < MAX_HOPS; hop++) {
    const response = await fetch(url, { redirect: 'manual', headers: { 'user-agent': MOBILE_UA }, signal: AbortSignal.timeout(TIMEOUT) })
    if (response.status >= 300 && response.status < 400) {
      const next = new URL(response.headers.get('location') || '', url)
      if (next.protocol !== 'https:' || !LANDING_HOST.test(next.hostname)) throw new Error('分享链接跳到了抖音以外的地址，没有继续打开')
      url = next.href
      continue
    }
    if (!response.ok) throw new Error(`抖音分享页打不开（${response.status}）`)
    const id = extractWebRid(await response.text())
    if (!id) throw new Error('分享页里没有找到直播间：可能不是直播间的分享链接，或抖音改了页面')
    return id
  }
  throw new Error('分享链接跳转次数过多')
}

/** 分享链接先换成直播间号；其余按直播间号或直播间网址处理。 */
export async function resolveRoom(input, options) {
  return normalizeRoomId(extractShareLink(input) ? await resolveShareLink(input, options) : input)
}
