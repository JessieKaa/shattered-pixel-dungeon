<template>
  <div class="nested-list">
    <div v-if="!value || value.length === 0" class="empty-hint">(空 list)</div>
    <div v-else-if="isSearching && visibleItems.length === 0" class="empty-hint">未找到匹配物品</div>

    <template v-if="isSearching">
      <el-card v-for="{ item, index } in visibleItems" :key="localKeys[index]" class="item-card" shadow="hover">
        <div class="item-row">
          <span class="drag-handle disabled" title="搜索时不可拖拽排序">⋮⋮</span>
          <strong class="item-title">
            #{{ index }} · <ItemLabel :class-name="(item as any)?.__className" />
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
          :value="item"
          :field-path="itemPath(index)"
          :search-query="itemSelfMatches(item) ? '' : searchQuery"
          @update="(newItem) => onItemFieldUpdate(index, newItem)"
        />
      </el-card>
    </template>

    <VueDraggable
      v-else-if="value && value.length > 0"
      v-model="localItems"
      :animation="150"
      handle=".drag-handle"
      @end="onDragEnd"
    >
      <el-card
        v-for="(element, index) in localItems"
        :key="localKeys[index]"
        class="item-card"
        shadow="hover"
      >
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
          :search-query="searchQuery"
          @update="(newItem) => onItemFieldUpdate(index, newItem)"
        />
      </el-card>
    </VueDraggable>

    <AddItemDialog @add="onItemAdd" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { VueDraggable } from 'vue-draggable-plus'
import NestedObject from './NestedObject.vue'
import AddItemDialog from './AddItemDialog.vue'
import ItemLabel from './ItemLabel.vue'
import {
  itemSearchParts,
  listItemMatchesQuery,
  matchesQuery,
  normalizeSearchText,
} from '@/composables/useFormSearch'

const props = defineProps<{
  value: unknown[]
  fieldKey?: string
  fieldPath?: string
  searchQuery?: string
}>()

const emit = defineEmits<{
  (e: 'update', value: unknown[]): void
}>()

// localItems 直接保存 item 本身(不包装)。stable UI key 在 parallel 数组里维护。
const localItems = ref<unknown[]>([])
const localKeys = ref<string[]>([])
const searchQuery = computed(() => props.searchQuery ?? '')
const isSearching = computed(() => normalizeSearchText(searchQuery.value).length > 0)
const visibleItems = computed(() => {
  return localItems.value
    .map((item, index) => ({ item, index }))
    .filter(({ item, index }) => !isSearching.value || listItemMatchesQuery(item, searchQuery.value, itemPath(index)))
})

function itemSelfMatches(item: unknown): boolean {
  return matchesQuery(searchQuery.value, ...itemSearchParts((item as Record<string, unknown> | null)?.__className))
}

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

function emitUpdate() {
  emit('update', localItems.value.slice())
}

function onDragEnd() {
  // VueDraggable 的 v-model 已经把 localItems 重排为 SortableJS 的 DOM 顺序;
  // emit 让父更新 store,props.value 回流后 watch 会用 prevByKey(item 引用)
  // 重新对齐 localKeys,无需在此手动同步。
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

.drag-handle.disabled {
  color: var(--el-text-color-secondary);
  cursor: not-allowed;
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
