// 对象存储的签名链接每次请求都会重新签（签名里带当前时间），同一张图每次拿到的地址都不同，
// 浏览器缓存就用不上：刷新、轮询、来回切页面都会把几 MB 的原图重新下一遍。
// 这里按「文件地址（不含签名）」记住第一次拿到的链接，在它过期前两分钟内一直复用同一个，
// 浏览器就能直接用缓存。只存在当前标签页（sessionStorage），刷新后仍有效，关掉标签页就没了。
const STORAGE_KEY = 'yifangzhi-signed-urls'
const SAFETY_MS = 2 * 60 * 1000
const MAX_ENTRIES = 400

/** 链接什么时候过期（毫秒时间戳）；认不出签名格式时返回 null，不缓存。 */
export function signedUrlExpiry(url) {
  let parsed
  try { parsed = new URL(url) } catch { return null }
  const cos = parsed.searchParams.get('q-sign-time')
  if (cos && /^\d+;\d+$/.test(cos)) return Number(cos.split(';')[1]) * 1000
  const amzDate = parsed.searchParams.get('X-Amz-Date')
  const amzExpires = Number(parsed.searchParams.get('X-Amz-Expires'))
  const match = amzDate && /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(amzDate)
  if (match && amzExpires > 0) {
    const [, y, mo, d, h, mi, s] = match.map(Number)
    return Date.UTC(y, mo - 1, d, h, mi, s) + amzExpires * 1000
  }
  return null
}

export function createStableUrls(storage, now = () => Date.now()) {
  let entries = null
  const load = () => {
    if (entries) return entries
    try { entries = JSON.parse(storage?.getItem(STORAGE_KEY) || '{}') || {} } catch { entries = {} }
    return entries
  }
  const save = () => {
    const current = now()
    const kept = Object.entries(entries).filter(([, entry]) => entry.expires - SAFETY_MS > current)
      .sort((a, b) => b[1].expires - a[1].expires).slice(0, MAX_ENTRIES)
    entries = Object.fromEntries(kept)
    try { storage?.setItem(STORAGE_KEY, JSON.stringify(entries)) } catch { /* 存不下就只在内存里复用 */ }
  }
  return function stableUrl(url) {
    if (typeof url !== 'string' || !/^https?:\/\//.test(url)) return url
    const expires = signedUrlExpiry(url)
    if (!expires) return url
    const key = url.split('?')[0]
    const known = load()[key]
    if (known && known.expires - SAFETY_MS > now()) return known.url
    entries[key] = { url, expires }
    save()
    return url
  }
}

const browserStorage = (() => {
  try { return typeof window === 'undefined' ? null : window.sessionStorage } catch { return null }
})()

export const stableUrl = createStableUrls(browserStorage)
