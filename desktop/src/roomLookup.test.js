import test from 'node:test'
import assert from 'node:assert/strict'
import { extractShareLink, extractWebRid, normalizeRoomId, resolveRoom, resolveShareLink } from './roomLookup.js'

const page = (status, body, headers = {}) => ({
  status, ok: status >= 200 && status < 300,
  headers: { get: name => headers[name.toLowerCase()] ?? null },
  text: async () => body,
})
/** Answers each URL from the table and records what was asked for. */
const web = (table) => {
  const calls = []
  const fetch = async (url, options) => { calls.push({ url, options }); return table[url] ?? page(404, '') }
  return { fetch, calls }
}
const LINK = 'https://v.douyin.com/eTtr9w0D3gY/'
const LANDING = 'https://webcast.amemv.com/douyin/webcast/reflow/7694004416784943922?u_code=abc'
const landed = { [LINK]: page(302, '', { location: LANDING }), [LANDING]: page(200, '<script>{\\"webRid\\":\\"850050208045\\"}</script>') }

test('a room id is taken as typed or out of a live.douyin.com address, and nothing else is accepted', () => {
  assert.equal(normalizeRoomId(' 850050208045 '), '850050208045')
  assert.equal(normalizeRoomId('https://live.douyin.com/850050208045?enter_from=web'), '850050208045')
  assert.equal(normalizeRoomId('live.douyin.com/shop_abc.1'), 'shop_abc.1')
  for (const bad of ['', null, '123/../admin', '8500 5020', 'https://example.com/850050208045']) {
    assert.throws(() => normalizeRoomId(bad), /没有认出直播间/)
  }
})

test('the short link is picked out of whatever was copied along with it', () => {
  assert.equal(extractShareLink(LINK), LINK)
  assert.equal(extractShareLink('3.84 复制打开抖音，看看【小店的直播】 https://v.douyin.com/eTtr9w0D3gY/ w@f.oD 05/21'), LINK)
  assert.equal(extractShareLink('http://v.douyin.com/i-AB_cd9'), 'https://v.douyin.com/i-AB_cd9/')
  for (const other of ['', '850050208045', 'eTtr9w0D3gY', 'https://live.douyin.com/850050208045', 'https://v.douyin.com.evil.test/abcd1234/']) {
    assert.equal(extractShareLink(other), null)
  }
})

test('the room id is read from the landing page whether or not its data is escaped', () => {
  assert.equal(extractWebRid('<script>{"room":{"webRid":"850050208045","title":"x"}}</script>'), '850050208045')
  assert.equal(extractWebRid('self.__pace_f.push([1,"{\\"webRid\\":\\"850050208045\\",\\"shortId\\":577937233}"])'), '850050208045')
  assert.equal(extractWebRid('<html>没有直播间信息</html>'), null)
})

test('the link is opened the way a phone would and the room id comes back', async () => {
  const { fetch, calls } = web(landed)
  assert.equal(await resolveShareLink(`看看直播 ${LINK} 复制`, { fetch }), '850050208045')
  assert.deepEqual(calls.map(call => call.url), [LINK, LANDING])
  assert.equal(calls[0].options.redirect, 'manual')
  assert.match(calls[0].options.headers['user-agent'], /iPhone/)
})

test('a redirect that leaves Douyin is not followed', async () => {
  for (const location of ['https://example.com/landing', 'http://webcast.amemv.com/x', 'https://amemv.com.evil.test/x', 'http://169.254.169.254/latest/meta-data']) {
    const { fetch, calls } = web({ 'https://v.douyin.com/abcd1234/': page(302, '', { location }) })
    await assert.rejects(resolveShareLink('https://v.douyin.com/abcd1234/', { fetch }), /抖音以外的地址/)
    assert.equal(calls.length, 1)
  }
})

test('a page without a room, a failing page and an endless redirect each say what went wrong', async () => {
  const link = 'https://v.douyin.com/abcd1234/'
  await assert.rejects(resolveShareLink(link, { fetch: web({ [link]: page(200, '<html>一条视频</html>') }).fetch }), /没有找到直播间/)
  await assert.rejects(resolveShareLink(link, { fetch: web({ [link]: page(503, '') }).fetch }), /打不开（503）/)
  const loop = web({ [link]: page(302, '', { location: link }) })
  await assert.rejects(resolveShareLink(link, { fetch: loop.fetch }), /跳转次数过多/)
  assert.equal(loop.calls.length, 5)
})

test('whatever the merchant pastes ends up as a room id, and only a share link costs a request', async () => {
  const { fetch, calls } = web(landed)
  assert.equal(await resolveRoom(`看看直播 ${LINK} 复制`, { fetch }), '850050208045')
  assert.equal(calls.length, 2)
  assert.equal(await resolveRoom(' 850050208045 ', { fetch }), '850050208045')
  assert.equal(await resolveRoom('https://live.douyin.com/850050208045', { fetch }), '850050208045')
  assert.equal(calls.length, 2)
  await assert.rejects(resolveRoom('http://127.0.0.1:18080/api/stores', { fetch }), /没有认出直播间/)
  // A landing page cannot smuggle in something that is not a room id.
  const odd = web({ [LINK]: page(200, '{"webRid":"../admin"}') })
  await assert.rejects(resolveRoom(LINK, { fetch: odd.fetch }), /没有找到直播间|没有认出直播间/)
})
