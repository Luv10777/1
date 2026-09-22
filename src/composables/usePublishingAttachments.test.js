import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { usePublishingAttachments } from './usePublishingAttachments.js'

test('attachment transactions preserve existing media and release object URLs', async () => {
  const originalImage = globalThis.Image
  const originalDocument = globalThis.document
  const originalCreate = URL.createObjectURL
  const originalRevoke = URL.revokeObjectURL
  const files = new Map()
  const revoked = new Set()
  let id = 0
  URL.createObjectURL = file => { const url = `blob:test-${++id}`; files.set(url, file); return url }
  URL.revokeObjectURL = url => revoked.add(url)
  class DecodedMedia {
    naturalWidth = 640
    naturalHeight = 480
    duration = 12
    set src(url) {
      queueMicrotask(() => {
        if (files.get(url)?.name.includes('corrupt')) this.onerror?.()
        else (this.onload || this.onloadeddata)?.()
      })
    }
    removeAttribute() {}
    load() {}
  }
  globalThis.Image = DecodedMedia
  globalThis.document = { createElement: () => new DecodedMedia() }
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
  let media
  const app = renderer.createApp({ setup() { media = usePublishingAttachments(); return () => null } })
  app.mount({})
  const picture = name => ({ name, size: 1024, type: 'image/png' })
  try {
    assert.equal(await media.addPictures([picture('first.png')]), true)
    const first = media.pictures.value[0]
    assert.equal(await media.addPictures([picture('valid.png'), picture('corrupt.png')]), false)
    assert.equal(media.pictures.value.length, 1)
    assert.equal(media.pictures.value[0].url, first.url)
    assert.equal(revoked.has(first.url), false)
    assert.equal(revoked.size, 2)

    assert.equal(await media.addPictures(Array.from({ length: 9 }, (_, index) => picture(`${index}.png`))), false)
    assert.match(media.error.value, /最多添加/)
    assert.equal(await media.addPictures([{ ...picture('huge.png'), size: 21 * 1024 * 1024 }]), false)
    assert.equal(media.pictures.value.length, 1)
    assert.equal(await media.addPictures([picture('second.png')]), true)
    media.movePicture(1, -1)
    assert.equal(media.pictures.value[0].name, 'second.png')

    assert.equal(await media.selectMusic({ name: 'track.mp3', size: 1024 }), true)
    const musicUrl = media.music.value.url
    assert.equal(await media.selectMusic({ name: 'corrupt.mp3', size: 1024 }), false)
    assert.equal(media.music.value.url, musicUrl)
    assert.equal(revoked.has(musicUrl), false)
    media.removeMusic()
    assert.equal(revoked.has(musicUrl), true)
    app.unmount()
    assert.equal([...files.keys()].every(url => revoked.has(url)), true)
  } finally {
    app.unmount()
    globalThis.Image = originalImage
    globalThis.document = originalDocument
    URL.createObjectURL = originalCreate
    URL.revokeObjectURL = originalRevoke
  }
})
