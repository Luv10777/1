import { get, post, put, del } from '../utils/request.js'

export const storesApi = {
  list: () => get('/api/stores'),
  create: values => post('/api/stores', values),
  update: (id, values) => put(`/api/stores/${id}`, values),
  archive: id => del(`/api/stores/${id}`),
}
