import { ref } from 'vue'

export const nestedFolders = ref([{ id:'2026 秋季营销', label:'2026 秋季营销', count:8, scope:'mine' }, { id:'品牌视觉', label:'品牌视觉', count:5, scope:'shared' }, { id:'产品图集', label:'产品图集', count:5, scope:'mine' }, { id:'社媒投放', label:'社媒投放', count:3, scope:'shared' }])
export const assets = ref([
  { id:'folder-1', kind:'folder', name:'KV 主视觉', meta:'8 个项目 · 4.2 GB', folder:'2026 秋季营销', scope:'mine' }, { id:'folder-2', kind:'folder', name:'短视频分镜', meta:'12 个项目 · 1.8 GB', folder:'2026 秋季营销', scope:'mine' },
  { id:'image-1', kind:'image', name:'茶园晨雾.jpg', meta:'2.4 MB · 昨天', folder:'2026 秋季营销', src:'/images/product-wall/sofa-texture.png', format:'JPG' }, { id:'image-2', kind:'image', name:'桂花乌龙-产品图.png', meta:'4.8 MB · 昨天', folder:'2026 秋季营销', src:'/images/product-wall/pasta-special.png', format:'PNG' }, { id:'image-3', kind:'image', name:'秋日礼盒-细节.jpg', meta:'3.1 MB · 8月28日', folder:'2026 秋季营销', src:'/images/product-wall/leather-look.png', format:'JPG' },
  { id:'video-1', kind:'video', name:'秋日品牌片-30s.mp4', meta:'86.2 MB · 8月27日', folder:'2026 秋季营销', duration:'0:30', format:'MP4', src:'/images/product-wall/rug-benefits.jpg' }, { id:'video-2', kind:'video', name:'门店氛围-竖版.mp4', meta:'42.7 MB · 8月26日', folder:'2026 秋季营销', duration:'0:15', format:'MP4', src:'/images/product-wall/floral-nails.png' }, { id:'audio-1', kind:'audio', name:'品牌片-环境音.mp3', meta:'8.6 MB · 8月25日', folder:'2026 秋季营销', duration:'1:20', format:'MP3' }, { id:'doc-1', kind:'document', name:'秋季营销-素材清单.pdf', meta:'1.2 MB · 8月24日', folder:'2026 秋季营销', format:'PDF' },
  { id:'loose-1', kind:'image', name:'临时拍摄-花絮.jpg', meta:'2.8 MB · 今天', folder:null, scope:'mine', src:'/images/product-wall/beef-pizza.png', format:'JPG' }, { id:'loose-2', kind:'image', name:'门店现场-未归档.png', meta:'5.6 MB · 昨天', folder:null, scope:'shared', src:'/images/product-wall/sink-product.png', format:'PNG' }
])

// Video thumbnails are not playable sources. The API should supply mediaUrl.
export function assetSource(asset) {
  return asset.kind === 'image' ? asset.mediaUrl || asset.src : asset.mediaUrl || ''
}
