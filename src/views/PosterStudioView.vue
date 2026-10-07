<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import ImageWorkspaceSwitch from '../components/ImageWorkspaceSwitch.vue'
import PosterControlPanel from '../components/PosterControlPanel.vue'
import PosterGalleryView from '../components/PosterGalleryView.vue'
import { useImageCreation } from '../composables/useImageCreation'
import '../poster-workbench.css'

const studio = useImageCreation('POSTER')
const control = ref(null)
const gallery = ref(null)
const prompted = ref(false)
let pulseTimer
function chooseScenario(scenario) {
  studio.style = scenario.focus
  studio.brief = scenario.prompt
  prompted.value = false
  nextTick(() => {
    prompted.value = true
    control.value?.focus()
    clearTimeout(pulseTimer)
    pulseTimer = setTimeout(() => { prompted.value = false }, 700)
  })
}
onBeforeUnmount(() => clearTimeout(pulseTimer))
watch(() => [studio.optimistic, studio.current?.id], async ([optimistic, id], [wasOptimistic, previousId] = []) => {
  if ((!optimistic || wasOptimistic) && (!id || id === previousId)) return
  if (!window.matchMedia('(max-width: 760px)').matches) return
  await nextTick()
  gallery.value?.$el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
})
</script>

<template>
  <section class="poster-workbench bg-[#FAF8F5] text-[#2D2826]" aria-label="营销海报工作区">
    <PosterControlPanel ref="control" :studio="studio" :prompted="prompted" />
    <div class="poster-showcase">
      <div class="poster-showcase-head">
        <div class="poster-showcase-copy"><h2>让门店的故事，一眼被看见。</h2><p>从真实商品与经营场景出发，做一张值得顾客停留的海报。</p></div>
        <ImageWorkspaceSwitch />
      </div>
      <PosterGalleryView ref="gallery" :studio="studio" @choose="chooseScenario" />
      <footer class="poster-showcase-footer"><img src="/images/brand/yifangzhi-mark.png" alt="" /><span>为每一方商家立传</span><small>一物 · 一景 · 一方志</small></footer>
    </div>
  </section>
</template>
