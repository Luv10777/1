<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import React from "react";
import { createRoot, type Root } from "react-dom/client";
import ServiceRulesWorkbench from "./ServiceRulesWorkbench";
import "../service-rules-tailwind.css";

const host = ref<HTMLElement | null>(null);
let root: Root | undefined;

onMounted(() => {
  if (host.value) {
    root = createRoot(host.value);
    root.render(React.createElement(ServiceRulesWorkbench));
  }
});

onBeforeUnmount(() => root?.unmount());
</script>

<template><div ref="host" class="service-rules-react-host" /></template>

<style scoped>
.service-rules-react-host {
  min-width: 0;
  max-width: none !important;
}
</style>
