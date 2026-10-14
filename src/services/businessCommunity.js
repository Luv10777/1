import { del, get, post, put } from '../utils/request.js'

async function submit(endpoint, values, expectedStatus) {
  let receipt
  try {
    receipt = await post(endpoint, values)
  } catch (failure) {
    if ([404, 405, 501].includes(failure.status)) {
      throw new Error('提交服务尚未接通，当前资料未提交。请稍后重试。')
    }
    if (failure.code === 'NETWORK_ERROR' || failure.status >= 500) {
      throw new Error('暂时无法确认提交结果，请保留当前内容，稍后联系平台确认。')
    }
    throw failure
  }
  if (!receipt?.id || receipt.status !== expectedStatus) {
    throw new Error('未收到有效的业务回执，请保留当前内容并联系平台确认。')
  }
  return receipt
}

export const businessCommunityService = {
  submitInformation: values => submit('/api/information-publications', values, 'PUBLISHED'),
  applyForAlliance: values => submit('/api/merchant-alliance/applications', values, 'PENDING_REVIEW'),
  applications: () => get('/api/merchant-alliance/applications'),
  publications: () => get('/api/information-publications'),
  updatePublication: (id, values) => put(`/api/information-publications/${id}`, values),
  takePublicationOffline: id => del(`/api/information-publications/${id}`),
  publicFeed: params => get('/api/public/information', Object.fromEntries(Object.entries(params || {}).filter(([, value]) => value != null && value !== ''))),
  publicInformation: id => get(`/api/public/information/${id}`),
}
