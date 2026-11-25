import { reactive } from 'vue'
import { del, get, post, put } from '../utils/request.js'

/**
 * 作品库：生成完的图片和视频由用户手动保存，可以放进文件夹（只有一层）。
 * kind 是 IMAGE（图片 ID，即 image item）或 VIDEO（视频 ID，即 video workflow）。
 */
export const libraryApi = {
  folders: () => get('/api/library/folders'),
  createFolder: name => post('/api/library/folders', { name }),
  renameFolder: (id, name) => put(`/api/library/folders/${id}`, { name }),
  /** 删除文件夹时，里面的作品一起移出作品库。 */
  deleteFolder: id => del(`/api/library/folders/${id}`),
  /** folder：all（全部）、root（未归类）或文件夹 ID；kind 为空表示图片视频都要。 */
  works: ({ folder = 'all', kind = '', page = 0, size = 20 } = {}) =>
    get('/api/library/works', { folder, ...(kind ? { kind } : {}), page, size }),
  save: (kind, sourceId, folderId = null) => post('/api/library/works', { kind, sourceId, folderId }),
  move: (workId, folderId) => put(`/api/library/works/${workId}/folder`, { folderId }),
  /** 批量移动，一次最多 100 件；有一件不在作品库里就整批不动。folderId 为空表示移回未归类。 */
  moveMany: (workIds, folderId) => put('/api/library/works/folder', { workIds, folderId }),
  remove: workId => del(`/api/library/works/${workId}`),
  saved: (kind, sourceIds) => get('/api/library/saved', { kind, sourceIds: sourceIds.join(',') }),
}

// 生成页上每件作品各有一个「保存到作品库」按钮；同一轮渲染里的查询合并成一次请求。
// 每件作品的状态是一个独立的响应式对象，容器本身不是响应式的，组件在 computed 里取状态不会触发自身。
const savedStates = new Map()
const queued = { IMAGE: new Set(), VIDEO: new Set() }
let flushScheduled = false

function stateOf(kind, sourceId) {
  const key = `${kind}-${sourceId}`
  let state = savedStates.get(key)
  if (!state) {
    state = reactive({ loaded: false, saved: false, folderId: null, folderName: null })
    savedStates.set(key, state)
  }
  return state
}

/** 返回这件作品的保存状态：{ loaded, saved, folderId, folderName }，首次读取时向后端查询。 */
export function librarySavedState(kind, sourceId) {
  const fresh = !savedStates.has(`${kind}-${sourceId}`)
  const state = stateOf(kind, sourceId)
  if (fresh) {
    queued[kind].add(sourceId)
    if (!flushScheduled) {
      flushScheduled = true
      queueMicrotask(flushSavedQueries)
    }
  }
  return state
}

async function flushSavedQueries() {
  flushScheduled = false
  for (const kind of Object.keys(queued)) {
    const ids = [...queued[kind]]
    queued[kind].clear()
    for (let start = 0; start < ids.length; start += 100) {
      const batch = ids.slice(start, start + 100)
      try {
        const rows = await libraryApi.saved(kind, batch)
        for (const row of rows) markLibrarySaved(kind, row.sourceId, row)
      } catch {
        // 查不到就当作没保存，按钮仍然可用；再保存一次是幂等的。
      }
      for (const id of batch) stateOf(kind, id).loaded = true
    }
  }
}

export function markLibrarySaved(kind, sourceId, { folderId = null, folderName = null } = {}) {
  Object.assign(stateOf(kind, sourceId), { loaded: true, saved: true, folderId, folderName })
}

export function markLibraryRemoved(kind, sourceId) {
  Object.assign(stateOf(kind, sourceId), { loaded: true, saved: false, folderId: null, folderName: null })
}

/** 文件夹改名后，生成页上已经显示的保存位置跟着变；folderName 为空表示文件夹被删，里面的作品已移出作品库。 */
export function syncLibraryFolder(folderId, folderName) {
  for (const state of savedStates.values()) {
    if (state.folderId !== folderId) continue
    if (folderName == null) Object.assign(state, { saved: false, folderId: null, folderName: null })
    else state.folderName = folderName
  }
}
