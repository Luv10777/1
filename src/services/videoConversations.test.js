import test from 'node:test'
import assert from 'node:assert/strict'
import { isVideoInProgress } from './videoConversations.js'

test('shared video state checks treat CANCELLED as terminal in both video screens', () => {
  for (const status of ['SUCCEEDED', 'FAILED', 'CANCELLED']) {
    assert.equal(isVideoInProgress({ status }), false, status)
  }
  for (const status of ['QUEUED', 'SUBMITTING', 'GENERATING', 'IMPORTING', 'QA']) {
    assert.equal(isVideoInProgress({ status }), true, status)
  }
  assert.equal(isVideoInProgress(null), false)
})
