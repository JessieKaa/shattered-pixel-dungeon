<template>
  <el-tooltip v-if="info" placement="top" effect="dark">
    <template #content>
      <div style="font-weight: 600">{{ info.zh }}</div>
      <div v-if="info.desc" style="font-size: 12px; opacity: 0.85; margin-top: 4px">{{ info.desc }}</div>
    </template>
    <span class="field-label">{{ info.zh }}<span class="raw-key">({{ path }})</span></span>
  </el-tooltip>
  <span v-else class="field-label">{{ path }}</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { getFieldLabel } from '@/composables/useFieldLabels'

const props = defineProps<{
  path: string
}>()

const info = computed(() => getFieldLabel(props.path))
</script>

<style scoped>
.field-label {
  display: inline-flex;
  align-items: center;
}

.raw-key {
  color: var(--el-text-color-secondary);
  font-size: 0.85em;
  margin-left: 4px;
  font-weight: normal;
}
</style>
