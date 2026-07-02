<template>
  <div class="nested-list">
    <div v-if="!value || value.length === 0" class="empty-hint">(空 list)</div>
    <draggable
      v-else
      :list="localItems"
      :item-key="getItemKey"
      :handle="'.drag-handle'"
      @end="onDragEnd"
    >
      <template #item="{ element, index }">
        <el-card class="item-card" shadow="hover">
          <div class="item-row">
            <span class="drag-handle" title="拖拽排序">⋮⋮</span>
            <strong class="item-title">
              #{{ index }} · <ItemLabel :class-name="(element as any)?.__className" />
            </strong>
            <el-popconfirm
              title="确认删除该项?"
              @confirm="onDelete(index)"
              width="200"
            >
              <template #reference>
                <el-button type="danger" size="small" link>删除</el-button>
              </template>
            </el-popconfirm>
          </div>

          <NestedObject
            :value="element"
            :field-path="itemPath(index)"
            @update="(newItem) => onItemFieldUpdate(index, newItem)"
          />
        </el-card>
      </template>
    </draggable>

    <AddItemDialog @add="onItemAdd" />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import draggable from 'vuedraggable'
import NestedObject from './NestedObject.vue'
import AddItemDialog from './AddItemDialog.vue'
import ItemLabel from './ItemLabel.vue'

const props = defineProps<{
  value: unknown[]
  fieldKey?: string
  fieldPath?: string
}>()

const emit = defineEmits<{
  (e: 'update', value: unknown[]): void
}>()

// localItems 直接保存 item 本身(不包装)。stable UI key 在 parallel 数组里维护。
const localItems = ref<unknown[]>([])
const localKeys = ref<string[]>([])

function makeId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return Math.random().toString(36).slice(2) + Date.now().toString(36)
}

function itemPath(index: number): string {
  const p = props.fieldPath?.trim()
  return p ? `${p}[${index}]` : `[${index}]`
}

watch(
  () => props.value,
  (v) => {
    const arr = Array.isArray(v) ? v : []
    // 尽量复用现有 key(若 item 引用相等);否则生成新 key。
    const prevByKey = new Map<unknown, string>()
    for (let i = 0; i < localItems.value.length; i++) {
      prevByKey.set(localItems.value[i], localKeys.value[i])
    }
    localItems.value = arr.slice()
    localKeys.value = arr.map((it) => prevByKey.get(it) ?? makeId())
  },
  { immediate: true, deep: true }
)

function getItemKey(item: unknown, index: number): string {
  return localKeys.value[index] ?? makeId()
}

function emitUpdate() {
  emit('update', localItems.value.slice())
}

function onDragEnd() {
  emitUpdate()
}

function onDelete(index: number) {
  localItems.value.splice(index, 1)
  localKeys.value.splice(index, 1)
  emitUpdate()
}

function onItemAdd(item: Record<string, unknown>) {
  localItems.value.push(item)
  localKeys.value.push(makeId())
  emitUpdate()
}

function onItemFieldUpdate(index: number, newItem: Record<string, unknown>) {
  localItems.value[index] = newItem
  emitUpdate()
}
</script>

<style scoped>
.item-card {
  margin: 8px 0;
}
.item-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.item-title {
  flex: 1;
}

@media (max-width: 768px) {
  .item-card {
    margin: 4px 0;
  }
  .item-row {
    flex-wrap: wrap;
    gap: 4px;
  }
  .item-title {
    flex: 1 1 100%;
    font-size: 0.9em;
  }
}
</style>
