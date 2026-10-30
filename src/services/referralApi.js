import { get, post, put } from '../utils/request'

export const referralApi = {
  me: () => get('/api/referral/me'),
  takeCode: () => post('/api/referral/code', { agreementAccepted: true }),
  rate: () => get('/api/platform/referral/rate'),
  updateRate: values => put('/api/platform/referral/rate', values),
}
