import test from 'node:test'
import assert from 'node:assert/strict'
import { closurePayload, createAccountApi, namePayload, passwordPayload, validateAvatar } from './accountApi.js'

test('avatars upload actual bytes directly, then save only the server-issued upload key to the current account', async () => {
  const file = new File(['image bytes'], 'portrait.png', { type: 'image/png' })
  const events = []
  const profile = { id: 7, avatarUrl: 'https://storage.test/saved.png' }
  const api = createAccountApi({
    request: async (path, options) => {
      events.push({ path, method: options.method, body: JSON.parse(options.body) })
      return path.endsWith('upload-url') ? { uploadKey: 'server-key', uploadUrl: 'https://storage.test/upload' } : profile
    },
    fetch: async (url, options) => {
      assert.equal(url, 'https://storage.test/upload')
      assert.equal(options.body, file)
      assert.deepEqual(options.headers, { 'Content-Type': 'image/png' })
      events.push({ upload: true })
      return { ok: true }
    },
  })
  assert.equal(await api.updateAvatar(file), profile)
  assert.deepEqual(events, [
    { path: '/api/auth/account/avatar/upload-url', method: 'POST', body: { mimeType: 'image/png' } },
    { upload: true },
    { path: '/api/auth/account/avatar', method: 'PUT', body: { uploadKey: 'server-key' } },
  ])
})

test('invalid avatars and failed uploads never save a profile', async () => {
  assert.throws(() => validateAvatar(new File(['svg'], 'x.svg', { type: 'image/svg+xml' })), /JPG/)
  assert.throws(() => validateAvatar({ type: 'image/png', size: 0 }), /2 MB/)
  assert.throws(() => validateAvatar({ type: 'image/png', size: 2 * 1024 * 1024 + 1 }), /2 MB/)
  const file = new File(['png'], 'avatar.png', { type: 'image/png' })
  for (const uploadFetch of [async () => ({ ok: false }), async () => { throw new Error('offline') }]) {
    const calls = []
    const api = createAccountApi({ request: async path => {
      calls.push(path)
      return { uploadKey: 'key', uploadUrl: 'https://storage.test/upload' }
    }, fetch: uploadFetch })
    await assert.rejects(api.updateAvatar(file), /上传失败/)
    assert.deepEqual(calls, ['/api/auth/account/avatar/upload-url'])
    await assert.rejects(api.updateAvatar({ type: 'image/png', size: 3 * 1024 * 1024 }), /2 MB/)
    assert.equal(calls.length, 1)
  }
})

test('a name is trimmed and must not be empty or too long', () => {
  assert.deepEqual(namePayload('  王老板 '), { name: '王老板' })
  assert.throws(() => namePayload('   '), /姓名/)
  assert.throws(() => namePayload('名'.repeat(81)), /80/)
})

test('a new password is checked locally and only the chosen proof is sent', () => {
  const values = { currentPassword: 'old-secret', code: ' 123456 ', newPassword: 'new-secret', confirmPassword: 'new-secret' }
  assert.deepEqual(passwordPayload(values, false), { currentPassword: 'old-secret', newPassword: 'new-secret' })
  assert.deepEqual(passwordPayload(values, true), { code: '123456', newPassword: 'new-secret' })
  assert.throws(() => passwordPayload({ ...values, newPassword: 'short', confirmPassword: 'short' }, false), /8–64/)
  assert.throws(() => passwordPayload({ ...values, confirmPassword: 'other-secret' }, false), /不一致/)
  assert.throws(() => passwordPayload({ ...values, currentPassword: '' }, false), /当前密码/)
  assert.throws(() => passwordPayload({ ...values, code: '12' }, true), /验证码/)
  // 25 个汉字是 75 个字节，超过服务端能校验的长度。
  const wide = '密'.repeat(25)
  assert.throws(() => passwordPayload({ ...values, newPassword: wide, confirmPassword: wide }, false), /太长/)
})

test('each action goes to the signed-in person’s own account, and an invalid form never reaches the server', async () => {
  const calls = []
  const api = createAccountApi({ request: async (endpoint, options) => {
    calls.push({ line: `${options.method} ${endpoint}`, body: options.body && JSON.parse(options.body) })
    return {}
  } })
  await api.profile()
  await api.rename(' 小李 ')
  await api.changePassword({ code: '123456', newPassword: 'new-secret', confirmPassword: 'new-secret' }, true)
  assert.deepEqual(calls.map(call => call.line),
    ['GET /api/auth/account', 'PUT /api/auth/account/name', 'PUT /api/auth/account/password'])
  assert.deepEqual(calls[1].body, { name: '小李' })
  assert.deepEqual(calls[2].body, { code: '123456', newPassword: 'new-secret' })
  assert.throws(() => api.rename(''), /姓名/)
  assert.equal(calls.length, 3)
})

test('closing an account needs the box ticked and only the chosen proof is sent', async () => {
  assert.deepEqual(closurePayload({ code: ' 123456 ', currentPassword: 'secret', confirmed: true }, true), { code: '123456', confirmed: true })
  assert.deepEqual(closurePayload({ code: '123456', currentPassword: 'secret', confirmed: true }, false), { currentPassword: 'secret', confirmed: true })
  assert.throws(() => closurePayload({ code: '123456', confirmed: false }, true), /勾选/)
  assert.throws(() => closurePayload({ code: '12', confirmed: true }, true), /验证码/)
  assert.throws(() => closurePayload({ currentPassword: '', confirmed: true }, false), /当前密码/)

  const calls = []
  const api = createAccountApi({ request: async (endpoint, options) => {
    calls.push({ line: `${options.method} ${endpoint}`, body: options.body && JSON.parse(options.body) })
    return {}
  } })
  await api.closurePreview()
  await api.close({ code: '123456', confirmed: true }, true)
  assert.deepEqual(calls.map(call => call.line), ['GET /api/auth/account/closure', 'POST /api/auth/account/closure'])
  assert.deepEqual(calls[1].body, { code: '123456', confirmed: true })
  assert.throws(() => api.close({ code: '123456', confirmed: false }, true), /勾选/)
  assert.equal(calls.length, 2)
})
