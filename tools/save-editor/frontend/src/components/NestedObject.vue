<template>
  <div class="nested-dict">
    <div v-if="sortedKeys.length === 0" class="empty-hint">(空 dict)</div>
    <FieldRow
      v-for="k in sortedKeys"
      :key="k"
      :key-name="k"
      :value="(value as Record<string, unknown>)[k]"
      @update="(key, val) => onFieldUpdate(key, val)"
      @delete="(key) => onFieldDelete(key)"
    />

    <!-- 嵌套 dict/list 折叠区(由 FieldRow 已渲染则跳过,这里独立显示) -->
    <el-collapse v-if="nestedEntries.length > 0" v-model="activeNested">
      <el-collapse-item
        v-for="[k, v] in nestedEntries"
        :key="k"
        :title="`${k} (${summary(v)})`"
        :name="k"
      >
        <NestedObject
          v-if="inferType(v) === 'dict'"
          :value="v"
          @update="(key, val) => onFieldUpdate(k, val)"
          @delete="(key) => onFieldDelete(k)"
        />
        <NestedList
          v-else-if="inferType(v) === 'list'"
          :value="v as unknown[]"
          :field-key="k"
          @update="(val) => onFieldUpdate(k, val)"
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
import NestedList from './NestedList.vue'
import AddFieldDialog from './AddFieldDialog.vue'
import { inferType, formatClassName } from '@/composables/useFieldType'
import type { FieldType } from '@/types'

const props = defineProps<{ value: unknown }>()
const emit = defineEmits<{
  (e: 'update', key: string, value: unknown): void
  (e: 'delete', key: string): void
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

const allKeys = computed(() => Object.keys(local.value))
const sortedKeys = computed(() => {
  // __className 永远第一;其余字母序
  const keys = allKeys.value.filter(
    (k) => inferType(local.value[k]) !== 'dict' && inferType(local.value[k]) !== 'list'
  )
  keys.sort((a, b) => {
    if (a === '__className') return -1
    if (b === '__className') return 1
    return a.localeCompare(b)
  })
  return keys
})

const nestedEntries = computed(() => {
  const entries: [string, unknown][] = []
  for (const k of allKeys.value) {
    const t = inferType(local.value[k])
    if (t === 'dict' || t === 'list') entries.push([k, local.value[k]])
  }
  entries.sort((a, b) => a[0].localeCompare(b[0]))
  return entries
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

function onFieldUpdate(key: string, val: unknown) {
  // 触发响应式:写入新对象
  local.value = { ...local.value, [key]: val }
  emit('update', key, val)
  // 自身 update event 是字段级,父组件需要重建整个 dict
  // 这里 emit 多次事件以满足外层"用 path 写入"的需要
}

function onFieldDelete(key: string) {
  const next = { ...local.value }
  delete next[key]
  local.value = next
  emit('delete', key)
}

function onFieldAdd(name: string, type: FieldType) {
  const def = defaultValue(type)
  local.value = { ...local.value, [name]: def }
  emit('update', name, def)
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
</style>
