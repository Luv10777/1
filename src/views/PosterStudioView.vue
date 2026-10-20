<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
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
      <PosterGalleryView ref="gallery" :studio="studio" @choose="chooseScenario" />
    </div>
  </section>
</template>
