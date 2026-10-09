import test from 'node:test'
import assert from 'node:assert/strict'
import { linkInArguments, readLink } from './link.js'

test('the link from the web console carries the store being set up there', () => {
  assert.deepEqual(readLink('yifangzhi://live?store=4'), { storeId: 4 })
  assert.deepEqual(readLink('yifangzhi://live/?store=120045'), { storeId: 120045 })
})

test('a link without a store, as older pages send it, still counts as ours', () => {
  assert.deepEqual(readLink('yifangzhi://live'), { storeId: null })
  assert.deepEqual(readLink('yifangzhi://live?store='), { storeId: null })
})

test('anything but a plain store number is dropped, not passed on to the page', () => {
  for (const store of ['abc', '4abc', '-4', '0', '04', '4.5', '4 OR 1=1', '%3Cscript%3E', '9999999999999999']) {
    assert.deepEqual(readLink(`yifangzhi://live?store=${store}`), { storeId: null }, store)
  }
  // Only the store is read; whatever else a page puts in the link is ignored.
  assert.deepEqual(readLink('yifangzhi://live?store=7&session=9&next=https://example.com'), { storeId: 7 })
})

test('links that are not ours are not read at all', () => {
  for (const url of ['https://yifangzhi.com/digital-human?store=4', 'file:///etc/passwd', 'yifangzhi', '', null, undefined, '--store=4']) {
    assert.equal(readLink(url), null, String(url))
  }
})

test('on Windows the link arrives among the launch arguments', () => {
  assert.equal(linkInArguments(['C:\\Program Files\\yifangzhi\\一方志.exe', '--allow-file-access-from-files', 'yifangzhi://live?store=4']), 'yifangzhi://live?store=4')
  assert.equal(linkInArguments(['C:\\Program Files\\yifangzhi\\一方志.exe']), '')
  assert.equal(linkInArguments(['electron', '.', 'yifangzhi:not-a-link', 42]), '')
})
