import { get } from '../utils/request.js'

// 视频工作台的创作场景（设计见 docs/video-scenes.md）。列表由服务端给，分镜参考只留在服务端；
// 取不到列表时只剩自由创作，工作台的场景一栏隐藏。
export const FREE_SCENE = 'free'

export const fetchVideoScenes = () => get('/api/video/scenes')

/** 服务端对自由创作返回空，前端统一记成 free。 */
export const videoSceneKey = key => key || FREE_SCENE

// 场景封面图放在 public/images/video-scenes/，16:10；服务端新加的场景没有图时仍显示图标。
const SCENE_COVERS = new Set([FREE_SCENE, 'ecommerce', 'local_life', 'event', 'brand', 'drama'])
export const videoSceneCover = key => (SCENE_COVERS.has(key) ? `/images/video-scenes/${key}.webp` : '')

export const findVideoScene =(scenes, key) => scenes.find(scene => scene.key === videoSceneKey(key)) || null

/** 记录里显示的场景名；自由创作和已下线的场景不显示。 */
export const videoSceneName = (scenes, key) => (videoSceneKey(key) === FREE_SCENE ? '' : findVideoScene(scenes, key)?.name || '')

/** 请求里只在选了场景、或提示词来自方案时带上这两个字段，自由创作的请求和以前完全一样。 */
export const videoSceneFields = ({ sceneKey, planId }) => ({
  ...(videoSceneKey(sceneKey) !== FREE_SCENE && { sceneKey }),
  ...(planId != null && { planId }),
})
