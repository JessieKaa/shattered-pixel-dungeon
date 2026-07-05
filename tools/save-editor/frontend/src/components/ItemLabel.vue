<template>
  <span class="item-label">
    <span v-if="info.zh" class="zh">{{ info.zh }}</span>
    <span v-if="info.zh" class="en">({{ info.fallback }})</span>
    <span v-if="!info.zh">{{ info.fallback }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { itemLabel } from '@/composables/useItemLabel'

const props = defineProps<{ className: unknown }>()
const info = computed(() => itemLabel(props.className))
</script>

<style scoped>
.item-label .en {
  color: var(--el-text-color-secondary);
  font-size: 0.85em;
}
</style>
