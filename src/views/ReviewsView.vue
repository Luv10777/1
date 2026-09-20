<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import React from "react";
import { createRoot, type Root } from "react-dom/client";
import ReviewsWorkbench from "./ReviewsWorkbench";
import "../reviews-tailwind.css";
const host = ref<HTMLElement | null>(null);
let root: Root | undefined;
onMounted(() => {
  if (host.value) {
    root = createRoot(host.value);
    root.render(React.createElement(ReviewsWorkbench));
  }
});
onBeforeUnmount(() => root?.unmount());
</script>
<template><div ref="host" class="reviews-react-host" /></template>
<style scoped>
.reviews-react-host {
  min-width: 0;
  max-width: none !important;
}
</style>
