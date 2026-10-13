import { post } from '../utils/request.js'

// 前端提交契约：由后端保存申请并返回 { id, status: 'PENDING_REVIEW' }。
// growth-api 尚未实现这两个接口；不使用浏览器存储或模拟回执兜底。
async function submitForReview(endpoint, values) {
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
  if (!receipt?.id || receipt.status !== 'PENDING_REVIEW') {
    throw new Error('未收到有效的提交回执，请保留当前内容并联系平台确认。')
  }
  return receipt
}

export const businessCommunityService = {
  submitInformation: values => submitForReview('/api/information-publications', values),
  applyForAlliance: values => submitForReview('/api/merchant-alliance/applications', values),
}
