import { get } from '../utils/request'

export const taskUsageApi = {
  logs: params => get('/api/task-center/logs', params),
  detail: id => get(`/api/task-center/logs/${encodeURIComponent(id)}`),
}
