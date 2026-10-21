<script setup>
import { nextTick, ref, watch } from 'vue'
import PosterControlPanel from '../components/PosterControlPanel.vue'
import PosterGalleryView from '../components/PosterGalleryView.vue'
import { useImageCreation } from '../composables/useImageCreation'
import '../poster-workbench.css'

const studio = useImageCreation('POSTER')
const gallery = ref(null)
function chooseStyle(style) {
  studio.style = studio.style === style ? '帮我搭配' : style
}
watch(() => [studio.optimistic, studio.current?.id], async ([optimistic, id], [wasOptimistic, previousId] = []) => {
  if ((!optimistic || wasOptimistic) && (!id || id === previousId)) return
  if (!window.matchMedia('(max-width: 760px)').matches) return
  await nextTick()
  gallery.value?.$el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
})
</script>

<template>
  <section class="poster-workbench bg-[#FAF8F5] text-[#2D2826]" aria-label="营销海报工作区">
    <PosterControlPanel :studio="studio" />
    <div class="poster-showcase">
      <PosterGalleryView ref="gallery" :studio="studio" @choose="chooseStyle" />
    </div>
  </section>
</template>
