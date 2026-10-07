import test from 'node:test'
import assert from 'node:assert/strict'
import { createTeamApi, memberPayload } from './teamApi.js'

test('a new clerk needs a real phone number and a name; store choices are sent once each', () => {
  assert.deepEqual(memberPayload({ phone: ' 13900000002 ', name: ' 小李 ', storeIds: ['3', 3, 5] }, true),
    { name: '小李', storeIds: [3, 5], phone: '13900000002' })
  assert.throws(() => memberPayload({ phone: '12345', name: '小李', storeIds: [] }, true), /手机号/)
  assert.throws(() => memberPayload({ phone: '13900000002', name: '  ', storeIds: [] }, true), /姓名/)
  // 修改时手机号不可改，也不随请求发送；一家门店都不选是允许的。
  assert.deepEqual(memberPayload({ phone: '13900000002', name: '小李' }), { name: '小李', storeIds: [] })
})

test('each action on a member is its own request, and only the ones that carry data send a body', async () => {
  const calls = []
  const api = createTeamApi({ request: async (endpoint, options) => {
    calls.push({ line: `${options.method} ${endpoint}`, body: options.body && JSON.parse(options.body) })
    return { id: 7 }
  } })
  await api.list()
  await api.add({ phone: '13900000002', name: '小李', storeIds: [3] })
  await api.update(7, { name: '小李', storeIds: [3, 5] })
  await api.disable(7)
  await api.enable(7)
  await api.remove(7)
  assert.deepEqual(calls.map(call => call.line), ['GET /api/team/members', 'POST /api/team/members', 'PUT /api/team/members/7',
    'POST /api/team/members/7/disable', 'POST /api/team/members/7/enable', 'DELETE /api/team/members/7'])
  assert.deepEqual(calls[1].body, { name: '小李', storeIds: [3], phone: '13900000002' })
  assert.deepEqual(calls[2].body, { name: '小李', storeIds: [3, 5] })
  assert.equal(calls[3].body, undefined)
  assert.equal(calls[5].body, undefined)
})

test('an invalid form never reaches the server', async () => {
  let requested = false
  const api = createTeamApi({ request: async () => { requested = true } })
  assert.throws(() => api.add({ phone: '139', name: '小李', storeIds: [] }), /手机号/)
  assert.equal(requested, false)
})
