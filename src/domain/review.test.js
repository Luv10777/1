import test from 'node:test'
import assert from 'node:assert/strict'
import { approveReview, createPublicReplyDraft, createReviewCase, REVIEW_RISK, REVIEW_STATUS } from './review.js'

test('critical review is blocked from automatic approval', () => {
  const review = createReviewCase({ text: '存在食品安全事故风险' })
  assert.equal(review.risk, REVIEW_RISK.CRITICAL)
  assert.equal(review.status, REVIEW_STATUS.NEEDS_FIX)
  assert.throws(() => approveReview(review, 'usr_demo_001'), /CRITICAL_REVIEW_CANNOT_AUTO_APPROVE/)
})

test('low-risk reply can be drafted but still records review state', () => {
  const result = createPublicReplyDraft({ text: '口味建议', suggestedText: '谢谢你的建议，我们会转给门店团队。' })
  assert.equal(result.blocked, false)
  assert.equal(result.review.status, REVIEW_STATUS.PENDING)
  assert.match(result.text, /谢谢/)
})
