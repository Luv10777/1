import { request as defaultRequest } from '../utils/request.js'

const PHONE = /^1[3-9]\d{9}$/

/** 提交前先在本地拦一遍，省得为了明显的手误往返一次。服务端仍会再校验。 */
export function memberPayload(values, creating = false) {
  const name = String(values.name ?? '').trim()
  if (!name) throw new Error('请填写姓名')
  const payload = { name, storeIds: [...new Set((values.storeIds || []).map(Number))] }
  if (creating) {
    const phone = String(values.phone ?? '').trim()
    if (!PHONE.test(phone)) throw new Error('请输入有效的 11 位手机号')
    payload.phone = phone
  }
  return payload
}

export function createTeamApi({ request = defaultRequest } = {}) {
  const write = (endpoint, method, value) => request(endpoint, { method, ...(value === undefined ? {} : { body: JSON.stringify(value) }) })
  return {
    list: () => request('/api/team/members', { method: 'GET' }),
    add: values => write('/api/team/members', 'POST', memberPayload(values, true)),
    update: (id, values) => write(`/api/team/members/${id}`, 'PUT', memberPayload(values)),
    disable: id => write(`/api/team/members/${id}/disable`, 'POST'),
    enable: id => write(`/api/team/members/${id}/enable`, 'POST'),
    remove: id => write(`/api/team/members/${id}`, 'DELETE'),
  }
}

export const teamApi = createTeamApi()
