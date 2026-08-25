import test from 'node:test'
import assert from 'node:assert/strict'
import { capabilityMode, createConnector, createConnectorCallbackGuard, markConnectorConnected, CONNECTOR_STATUS } from './connectors.js'

test('connector defaults to export-only without platform authorization', () => {
  const connector = createConnector({ platform: 'xiaohongshu' })
  assert.equal(connector.status, CONNECTOR_STATUS.DISCONNECTED)
  assert.equal(capabilityMode(connector, 'publish'), 'EXPORT_ONLY')
})

test('connector records token lifecycle metadata', () => {
  const connector = createConnector({ platform: 'douyin' })
  const connected = markConnectorConnected(connector, { accountId: 'acct_demo', tokenExpiresAt: '2030-01-01T00:00:00Z' })
  assert.equal(connected.status, CONNECTOR_STATUS.CONNECTED)
  assert.equal(connected.accountId, 'acct_demo')
})

test('callback guard refuses unsigned callbacks by default', () => {
  assert.equal(createConnectorCallbackGuard().verify({}).valid, false)
})
