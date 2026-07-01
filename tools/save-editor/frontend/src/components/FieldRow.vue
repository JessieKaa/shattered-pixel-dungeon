<template>
  <div class="field-row">
    <label :class="{ 'readonly-label': isCN }" :title="hint">{{ keyName }}</label>

    <el-input
      v-if="isCN"
      :model-value="String(value ?? '')"
      readonly
      size="small"
      class="value-input"
    />

    <el-switch
      v-else-if="type === 'bool'"
      :model-value="!!value"
      @update:model-value="onUpdate"
    />

    <el-input-number
      v-else-if="type === 'int'"
      :model-value="Number(value ?? 0)"
      :step="1"
      :precision="0"
      @update:model-value="onUpdate"
      size="small"
      controls-position="right"
    />

    <el-input-number
      v-else-if="type === 'float'"
      :model-value="Number(value ?? 0)"
      :step="0.01"
      @update:model-value="onUpdate"
      size="small"
      controls-position="right"
    />

    <el-input
      v-else-if="type === 'null'"
      :model-value="localText"
      @update:model-value="onText"
      placeholder="(null — 留空保持 null)"
      size="small"
      class="value-input"
    />

    <el-input
      v-else-if="type === 'str'"
      :model-value="String(value ?? '')"
      @update:model-value="onUpdate"
      size="small"
      class="value-input"
    />

    <el-input
      v-else
      :model-value="String(value ?? '')"
      disabled
      size="small"
      class="value-input"
    />

    <span class="type-tag">({{ type }})</span>

    <el-popconfirm
      v-if="!isCN"
      title="确认删除该字段?"
      @confirm="$emit('delete', keyName)"
      width="200"
    >
      <template #reference>
        <el-button type="danger" size="small" link>删除</el-button>
      </template>
    </el-popconfirm>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { inferType } from '@/composables/useFieldType'

const props = defineProps<{
  keyName: string
  value: unknown
}>()

const emit = defineEmits<{
  (e: 'update', key: string, value: unknown): void
  (e: 'delete', key: string): void
}>()

const type = computed(() => inferType(props.value))
const isCN = computed(() => props.keyName === '__className')
const hint = computed(
  () => `(${type.value})${isCN.value ? ' · class identifier (read-only)' : ''}`
)

const localText = ref('')

function onUpdate(v: unknown) {
  emit('update', props.keyName, v)
}

function onText(v: string) {
  localText.value = v
  // 空字符串 → null;非空 → 原样返回字符串(用户责任)
  emit('update', props.keyName, v === '' ? null : v)
}
</script>

<style scoped>
.field-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

label {
  min-width: 220px;
  font-weight: 500;
}

label.readonly-label::after {
  content: ' (只读)';
  color: var(--el-text-color-secondary);
  font-weight: normal;
  font-size: 0.85em;
}

.value-input {
  flex: 1;
}

.type-tag {
  color: var(--el-text-color-secondary);
  font-size: 0.8em;
  font-family: monospace;
  min-width: 60px;
}
</style>
