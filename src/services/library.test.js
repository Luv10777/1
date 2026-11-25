import test from 'node:test'
import assert from 'node:assert/strict'
import { libraryApi, librarySavedState, markLibraryRemoved, markLibrarySaved, syncLibraryFolder } from './library.js'

test('saved states of the works on a page are looked up in one request per kind and follow folder changes', async t => {
  const calls = []
  t.mock.method(libraryApi, 'saved', async (kind, ids) => {
    calls.push([kind, ids])
    return kind === 'IMAGE' ? [{ sourceId: 2, workId: 9, folderId: 4, folderName: '七月海报' }] : []
  })
  const first = librarySavedState('IMAGE', 1)
  const second = librarySavedState('IMAGE', 2)
  const video = librarySavedState('VIDEO', 7)
  assert.equal(librarySavedState('IMAGE', 2), second, 'the same work shares one state')
  assert.equal(first.loaded, false)
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(calls, [['IMAGE', [1, 2]], ['VIDEO', [7]]])
  assert.deepEqual({ ...first }, { loaded: true, saved: false, folderId: null, folderName: null })
  assert.deepEqual({ ...second }, { loaded: true, saved: true, folderId: 4, folderName: '七月海报' })
  assert.equal(video.saved, false)

  markLibrarySaved('VIDEO', 7, { folderId: 4, folderName: '七月海报' })
  syncLibraryFolder(4, '暑期海报')
  assert.equal(second.folderName, '暑期海报')
  assert.equal(video.folderName, '暑期海报')
  // 删除文件夹，里面的作品都不在作品库了。
  syncLibraryFolder(4, null)
  assert.equal(second.saved, false)
  assert.equal(video.saved, false)

  markLibrarySaved('IMAGE', 1)
  markLibraryRemoved('IMAGE', 1)
  assert.equal(first.saved, false)
  librarySavedState('IMAGE', 1)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(calls.length, 2, 'a work already looked up is not queried again')
})
