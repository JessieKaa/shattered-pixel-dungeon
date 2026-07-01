<template>
  <div class="nested-list">
    <div v-if="!value || value.length === 0" class="empty-hint">
      (空 list)
    </div>
    <draggable
      v-else
      :list="localItems"
      :handle="'.drag-handle'"
      item-key="id"
      @end="onDragEnd"
    >
      <template #item="{ element, index }">
        <el-card class="item-card" shadow="hover">
          <div class="item-row">
            <span class="drag-handle" title="拖拽排序">⋮⋮</span>
            <strong class="item-title">
              #{{ index }} · {{ formatClassName((element as any)?.__className) }}
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
            @update="(key, val) => onItemFieldUpdate(index, key, val)"
            @delete="(key) => onItemFieldDelete(index, key)"
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
import { formatClassName } from '@/composables/useFieldType'

const props = defineProps<{
  value: unknown[]
  fieldKey?: string
}>()

const emit = defineEmits<{
  (e: 'update', value: unknown[]): void
}>()

interface ItemWithId { id: number; data: unknown }
let nextId = 1

const localItems = ref<ItemWithId[]>(
  props.value.map((d) => ({ id: nextId++, data: d }))
)

watch(
  () => props.value,
  (v) => {
    localItems.value = (v ?? []).map((d) => ({ id: nextId++, data: d }))
  }
)

function onDragEnd() {
  emit('update', localItems.value.map((it) => it.data))
}

function onDelete(index: number) {
  const next = localItems.value.slice()
  next.splice(index, 1)
  localItems.value = next
  emit('update', next.map((it) => it.data))
}

function onItemAdd(item: Record<string, unknown>) {
  const next = [...localItems.value, { id: nextId++, data: item }]
  localItems.value = next
  emit('update', next.map((it) => it.data))
}

function onItemFieldUpdate(index: number, key: string, val: unknown) {
  const target = localItems.value[index]
  if (!target) return
  target.data = { ...(target.data as object), [key]: val }
  emit('update', localItems.value.map((it) => it.data))
}

function onItemFieldDelete(index: number, key: string) {
  const target = localItems.value[index]
  if (!target) return
  const next = { ...(target.data as object) }
  delete (next as any)[key]
  target.data = next
  emit('update', localItems.value.map((it) => it.data))
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
</style>
