import { get, post } from '../utils/request.js'

/** 当前账号的站内通知。 */
export const noticeApi = {
  list: values => get('/api/notices', values),
  unread: () => get('/api/notices/unread'),
  read: id => post(`/api/notices/${encodeURIComponent(id)}/read`),
  readAll: () => post('/api/notices/read-all'),
}

/** 已读状态变了（在通知页里），通知顶栏刷新未读数。 */
export const NOTICES_CHANGED = 'notices-changed'
