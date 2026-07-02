<template>
  <div class="nested-dict">
    <div v-if="allKeys.length === 0" class="empty-hint">(空 dict)</div>

    <!-- 叶子字段(bool/int/float/str/null/unknown + 只读 __className)-->
    <FieldRow
      v-for="k in leafKeys"
      :key="k"
      :key-name="k"
      :field-path="childPath(k)"
      :value="local[k]"
      @update="(_key, val) => onLeafUpdate(k, val)"
      @delete="() => onLeafDelete(k)"
    />

    <!-- 嵌套 dict / list:递归 + collapse -->
    <el-collapse v-if="nestedEntries.length > 0" v-model="activeNested">
      <el-collapse-item
        v-for="[k, v] in nestedEntries"
        :key="k"
        :name="k"
      >
        <template #title>
          <span class="nested-title">
            <FieldLabel :path="childPath(k)" :key-name="k" />
            <span class="nested-summary">({{ summary(v) }})</span>
          </span>
        </template>
        <NestedObject
          v-if="inferType(v) === 'dict'"
          :value="local[k]"
          :field-path="childPath(k)"
          @update="(newVal) => onChildUpdate(k, newVal)"
        />
        <NestedList
          v-else-if="inferType(v) === 'list'"
          :value="v as unknown[]"
          :field-key="k"
          :field-path="childPath(k)"
          @update="(newVal) => onChildUpdate(k, newVal)"
        />
      </el-collapse-item>
    </el-collapse>

    <AddFieldDialog
      :existing-keys="allKeys"
      @add="(name, type) => onFieldAdd(name, type)"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import FieldRow from './FieldRow.vue'
import FieldLabel from './FieldLabel.vue'
import NestedList from './NestedList.vue'
import AddFieldDialog from './AddFieldDialog.vue'
import { inferType, formatClassName } from '@/composables/useFieldType'
import type { FieldType } from '@/types'

const props = defineProps<{
  value: unknown
  fieldPath?: string
}>()
const emit = defineEmits<{
  (e: 'update', newVal: Record<string, unknown>): void
}>()

const local = ref<Record<string, unknown>>({})

watch(
  () => props.value,
  (v) => {
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      local.value = { ...(v as Record<string, unknown>) }
    } else {
      local.value = {}
    }
  },
  { immediate: true, deep: true }
)

function childPath(k: string): string {
  const p = props.fieldPath?.trim()
  return p ? `${p}.${k}` : k
}

const allKeys = computed(() => Object.keys(local.value))

const leafKeys = computed(() => {
  return allKeys.value
    .filter((k) => {
      const t = inferType(local.value[k])
      return t !== 'dict' && t !== 'list'
    })
    .sort((a, b) => {
      if (a === '__className') return -1
      if (b === '__className') return 1
      return a.localeCompare(b)
    })
})

const nestedEntries = computed(() => {
  const entries: [string, unknown][] = []
  for (const k of allKeys.value) {
    const t = inferType(local.value[k])
    if (t === 'dict' || t === 'list') entries.push([k, local.value[k]])
  }
  return entries.sort((a, b) => a[0].localeCompare(b[0]))
})

const activeNested = ref<string[]>([])

function summary(v: unknown): string {
  const t = inferType(v)
  if (t === 'dict') {
    const cn = (v as any)?.__className
    return cn ? formatClassName(cn) : 'dict'
  }
  if (t === 'list') {
    const len = Array.isArray(v) ? v.length : 0
    const classes = Array.isArray(v)
      ? v.map((it) => formatClassName((it as any)?.__className)).join(', ')
      : ''
    return `list[${len}${classes ? ': ' + classes : ''}]`
  }
  return t
}

function emitUpdate() {
  emit('update', { ...local.value })
}

function onLeafUpdate(k: string, val: unknown) {
  local.value = { ...local.value, [k]: val }
  emitUpdate()
}

function onLeafDelete(k: string) {
  const next = { ...local.value }
  delete next[k]
  local.value = next
  emitUpdate()
}

function onChildUpdate(k: string, newVal: unknown) {
  local.value = { ...local.value, [k]: newVal }
  emitUpdate()
}

function onFieldAdd(name: string, type: FieldType) {
  const def = defaultValue(type)
  local.value = { ...local.value, [name]: def }
  emitUpdate()
}

function defaultValue(type: FieldType): unknown {
  switch (type) {
    case 'bool':
      return false
    case 'int':
      return 0
    case 'float':
      return 0.0
    case 'str':
      return ''
    case 'null':
      return null
    case 'list':
      return []
    case 'dict':
      return {}
    default:
      return null
  }
}
</script>

<style scoped>
.nested-dict {
  margin: 4px 0;
  padding-left: 8px;
}

.nested-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.nested-summary {
  color: var(--el-text-color-secondary);
  font-weight: normal;
  font-size: 0.9em;
}

@media (max-width: 768px) {
  .nested-dict {
    margin: 4px 0;
    padding-left: 4px;
  }
  .nested-summary {
    font-size: 0.8em;
  }
}
</style>
