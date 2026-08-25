import { PROVIDER_CAPABILITIES } from './creative.js'

export const CONNECTOR_STATUS = Object.freeze({ DISCONNECTED: 'DISCONNECTED', AUTHORIZING: 'AUTHORIZING', CONNECTED: 'CONNECTED', EXPIRED: 'EXPIRED', REVOKED: 'REVOKED' })

export function createConnector(input = {}) {
  return {
    id: input.id || `conn_${crypto.randomUUID()}`,
    tenantId: input.tenantId || 'tenant_demo_001',
    platform: input.platform,
    accountId: input.accountId || null,
    status: input.status || CONNECTOR_STATUS.DISCONNECTED,
    capabilities: input.capabilities || { publish: PROVIDER_CAPABILITIES.EXPORT_ONLY, comments: PROVIDER_CAPABILITIES.EXPORT_ONLY, analytics: PROVIDER_CAPABILITIES.EXPORT_ONLY },
    tokenExpiresAt: input.tokenExpiresAt || null,
    lastCallbackAt: null,
  }
}

export function markConnectorConnected(connector, input = {}) {
  if (!input.accountId || !input.tokenExpiresAt) throw new Error('CONNECTOR_AUTH_METADATA_REQUIRED')
  return { ...connector, accountId: input.accountId, status: CONNECTOR_STATUS.CONNECTED, tokenExpiresAt: input.tokenExpiresAt, capabilities: input.capabilities || connector.capabilities }
}

export function refreshConnector(connector, input = {}) {
  if (connector.status !== CONNECTOR_STATUS.CONNECTED && connector.status !== CONNECTOR_STATUS.EXPIRED) throw new Error('CONNECTOR_NOT_REFRESHABLE')
  if (!input.tokenExpiresAt) throw new Error('CONNECTOR_TOKEN_EXPIRY_REQUIRED')
  return { ...connector, status: CONNECTOR_STATUS.CONNECTED, tokenExpiresAt: input.tokenExpiresAt }
}

export function capabilityMode(connector, capability) {
  return connector.capabilities?.[capability] || PROVIDER_CAPABILITIES.UNAVAILABLE
}

export function createConnectorCallbackGuard({ secretConfigured = false } = {}) {
  return {
    verify({ signature, timestamp, nonce } = {}) {
      if (!secretConfigured) return { valid: false, reason: 'CALLBACK_SECRET_NOT_CONFIGURED' }
      if (!signature || !timestamp || !nonce) return { valid: false, reason: 'CALLBACK_METADATA_REQUIRED' }
      return { valid: true }
    },
  }
}
