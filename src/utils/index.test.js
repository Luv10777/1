import { describe, it } from 'node:test'
import assert from 'node:assert'
import { formatDate, formatFileSize, isEmpty, generateId } from '../utils/index.js'

describe('Utility Functions', () => {
  it('formatDate formats dates correctly', () => {
    const date = new Date('2026-08-25T12:30:45')
    assert.strictEqual(formatDate(date, 'YYYY-MM-DD'), '2026-08-25')
  })

  it('formatFileSize converts bytes to readable format', () => {
    assert.strictEqual(formatFileSize(0), '0 B')
    assert.strictEqual(formatFileSize(1024), '1 KB')
    assert.strictEqual(formatFileSize(1048576), '1 MB')
  })

  it('isEmpty detects empty values', () => {
    assert.strictEqual(isEmpty(null), true)
    assert.strictEqual(isEmpty(undefined), true)
    assert.strictEqual(isEmpty(''), true)
    assert.strictEqual(isEmpty('  '), true)
    assert.strictEqual(isEmpty([]), true)
    assert.strictEqual(isEmpty({}), true)
    assert.strictEqual(isEmpty('hello'), false)
    assert.strictEqual(isEmpty([1]), false)
  })

  it('generateId creates unique identifiers', () => {
    const id1 = generateId()
    const id2 = generateId()
    assert.notStrictEqual(id1, id2)
    assert.ok(id1.length > 10)
  })
})
