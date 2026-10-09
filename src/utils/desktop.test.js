import test from 'node:test'
import assert from 'node:assert/strict'
import { desktopLink } from './desktop.js'
import { readLink } from '../../desktop/src/link.js'

test('the link names the store being set up, in the form the desktop app reads', () => {
  assert.equal(desktopLink(4), 'yifangzhi://live?store=4')
  assert.deepEqual(readLink(desktopLink(4)), { storeId: 4 })
  assert.deepEqual(readLink(desktopLink('120045')), { storeId: 120045 })
})

test('with no store chosen yet the link only brings the app to the front', () => {
  assert.equal(desktopLink(null), 'yifangzhi://live')
  assert.deepEqual(readLink(desktopLink(undefined)), { storeId: null })
})
