# PLAN: form-search

## Goal

为存档编辑器的"表单 Schema"页增加模糊搜索,支持按英文字段/key/class 名和中文字段/item 名过滤护甲、武器、背包中的表单项。

示例:
- 搜 `生命` 能匹配中文字段名"生命值"(如果未来 Form Schema 内出现该字段)
- 搜 `quantity` 或 `数量` 能匹配 `quantity` 字段
- 搜 `cloth` 或 `布甲` 能匹配 `ClothArmor` item 卡片
- 搜 `wand` 或 `魔弹` 能匹配 `WandOfMagicMissile` 嵌套对象/折叠标题

## Context

### 为什么做

表单 Schema 编辑器已经支持字段中文名(field-labels-zh)和 item 中文名(item-labels-zh),但对象层级深、字段多。用户需要快速定位字段和背包 item,尤其是在移动端或物品列表较长时。

### 与现有功能的关系

- `FormSchemaEditor.vue` 当前只负责三个 tab:护甲、武器、背包
- `NestedObject.vue` 递归渲染 dict 字段和 nested dict/list
- `NestedList.vue` 渲染背包 item card 并递归进入 `NestedObject`
- `FieldLabel.vue` / `useFieldLabels.ts` 已提供字段中文名
- `ItemLabel.vue` / `useItemLabel.ts` 已提供 item 中文名

搜索应只影响渲染过滤,不改变 bundle 数据、不影响新增/删除/拖拽逻辑、不影响下载 zip。

### 关键决策

- **搜索入口放在 FormSchemaEditor header 下方、tabs 上方**,对护甲/武器/背包三个 tab 共享同一个 query
- **递归过滤**:
  - 叶子字段命中则显示该字段
  - nested dict/list 的 key、路径、中文字段名、summary 命中则显示整个 nested 节点
  - 如果 nested 子孙命中,则保留祖先节点并显示匹配子树
  - list 中 item 命中 class/item 中文名时显示该 item；item 子字段命中时也显示该 item
- **模糊搜索定义**:大小写不敏感 substring;中文直接 substring;去掉空格后再匹配一次(支持 `cur charge` → `curCharges` 的弱匹配)
- **展开行为**:搜索 query 非空时,`NestedObject` 自动展开有匹配结果的 nested collapse；清空搜索后恢复用户手动展开状态的常规行为
- **空结果**:当前 tab 内无匹配时显示 `未找到匹配字段` 提示,不清空数据
- **不做高亮**:本轮只做过滤,不做命中字符高亮,降低复杂度和 Element Plus 样式风险

## Files

### 新增

- `tools/save-editor/frontend/src/composables/useFormSearch.ts`
  - normalize/query matching helpers
  - 从 field path/key 获取中文字段 label
  - 从 className 获取中文 item label
  - 对 dict/list 做 recursive match/filter
- `tools/save-editor/tests/e2e/test_form_search.py`
  - Playwright + system Chrome,上传 fixture,验证中文/英文搜索

### 修改

- `tools/save-editor/frontend/src/components/FormSchemaEditor.vue`
  - 增加搜索输入框和清空按钮
  - 把 `search-query` prop 传给 `NestedObject` / `NestedList`
  - 显示当前 query 的轻量提示
- `tools/save-editor/frontend/src/components/NestedObject.vue`
  - 接收 `searchQuery?: string`
  - 用 search composable 过滤 `leafKeys` 和 `nestedEntries`
  - query 非空时自动展开匹配的 nested entries
  - 递归传 `search-query`
  - 无匹配时显示空结果提示
- `tools/save-editor/frontend/src/components/NestedList.vue`
  - 接收 `searchQuery?: string`
  - 过滤 item cards(class/item label/子字段命中)
  - query 非空时禁用拖拽排序或保持仅过滤显示但不允许拖动,避免 filtered list index 与原数组 index 错位
  - 递归传 `search-query`
- `tools/save-editor/frontend/src/components/FieldLabel.vue`(可选)
  - 若 composable 需要复用 display 文本,不一定改
- `tools/save-editor/frontend/src/composables/useFieldLabels.ts`
  - 若 `getFieldLabel` 已足够,不改
- `tools/save-editor/frontend/src/composables/useItemLabel.ts`
  - 若 `itemLabel` 已足够,不改

## Steps

### Current-code notes

- `FormSchemaEditor.vue` currently has only `active` state and passes `armor`/`weapon` to `NestedObject`, `inventory` to `NestedList`; add `searchQuery = ref('')` here and pass it to all three render roots.
- `NestedObject.vue` currently computes unfiltered `leafKeys` and `nestedEntries` from `local`; keep `allKeys` unchanged for `AddFieldDialog`, but filter only the render lists when searching.
- `NestedObject.summary()` already formats dict/list class summaries using `itemLabel`; export equivalent search parts from the new composable instead of importing component UI.
- `NestedList.vue` currently binds `<draggable :list="localItems">` and uses `index` directly for delete/update/path; when searching, render `visibleItems: { item, index }[]` outside draggable so every action still uses the real array index.
- `FieldRow.vue` renders rows as `.field-row`; `ItemLabel.vue` renders Chinese + fallback simple class; e2e can assert visible text through `.field-row`, `.item-title`, and `.el-collapse-item__header`.
- Existing e2e style is a standalone Python script with `sync_playwright`, system Chrome, manual `chk()`, URL `http://127.0.0.1:5010/`, and fixture `sample_save_with_cloth_armor.zip`.

### Step 1:设计搜索 helper

新增 `useFormSearch.ts`:

```ts
import { getFieldLabel } from '@/composables/useFieldLabels'
import { itemLabel } from '@/composables/useItemLabel'
import { inferType } from '@/composables/useFieldType'

export function normalizeSearchText(v: unknown): string {
  return String(v ?? '').toLowerCase().replace(/\s+/g, '')
}

export function matchesQuery(query: string, ...parts: unknown[]): boolean {
  const q = normalizeSearchText(query)
  if (!q) return true
  return parts.some((p) => normalizeSearchText(p).includes(q))
}

export function fieldSearchParts(path: string, keyName: string, value?: unknown): string[] {
  const info = getFieldLabel(path)
  return [path, keyName, info?.zh, info?.desc, inferType(value)]
}

export function itemSearchParts(className: unknown): string[] {
  const info = itemLabel(className)
  return [info.zh, info.fallback, className]
}
```

也可以把递归 match 做成函数:

```ts
export function objectMatchesQuery(value: unknown, query: string, path = ''): boolean
export function listItemMatchesQuery(value: unknown, query: string, path = ''): boolean
```

注意避免 deep watch/JSON.stringify 全量 stringify,只遍历 object key/value 和 list item。

### Step 2:FormSchemaEditor 搜索入口

在 card header 下方加:

```vue
<div class="form-search-bar">
  <el-input
    v-model="searchQuery"
    clearable
    placeholder="搜索字段 / item(支持中文、英文、class 名)"
    prefix-icon="Search"
  />
</div>
```

如果不想引入 Element Plus icon,不用 `prefix-icon`,只放 placeholder。

script:

```ts
const searchQuery = ref('')
```

传给三个入口:

```vue
<NestedObject :search-query="searchQuery" ... />
<NestedList :search-query="searchQuery" ... />
```

样式:

```css
.form-search-bar { margin-bottom: 12px; }
```

### Step 3:NestedObject 过滤

props 加:

```ts
searchQuery?: string
```

新增:

```ts
const normalizedQuery = computed(() => normalizeSearchText(props.searchQuery ?? ''))
const isSearching = computed(() => normalizedQuery.value.length > 0)
```

`leafKeys` 过滤:

```ts
.filter((k) => {
  const t = inferType(local.value[k])
  if (t === 'dict' || t === 'list') return false
  if (!isSearching.value) return true
  return matchesQuery(props.searchQuery ?? '', ...fieldSearchParts(childPath(k), k, local.value[k]), String(local.value[k] ?? ''))
})
```

`nestedEntries` 过滤:

```ts
if (!isSearching.value || nestedEntryMatches(k, local.value[k])) entries.push([k, local.value[k]])
```

`nestedEntryMatches` 应匹配:
- key/path/中文字段 label/desc
- `summary(v)`(含 item 中文名)
- 子孙字段/object/list item 的递归命中

当 `isSearching` 时自动展开:

```ts
watch([nestedEntries, isSearching], () => {
  if (isSearching.value) activeNested.value = nestedEntries.value.map(([k]) => k)
})
```

注意:清空搜索时不要重置 `activeNested`,让 Element Plus 保持当前状态即可。

空结果:

```vue
<div v-if="isSearching && leafKeys.length === 0 && nestedEntries.length === 0" class="empty-hint">
  未找到匹配字段
</div>
```

### Step 4:NestedList 过滤并保护拖拽

props 加:

```ts
searchQuery?: string
```

保留 `localItems` 作为真实数组,新增 displayed items 包装:

```ts
const isSearching = computed(() => normalizeSearchText(props.searchQuery ?? '').length > 0)
const visibleItems = computed(() => {
  return localItems.value
    .map((item, index) => ({ item, index }))
    .filter(({ item, index }) => !isSearching.value || listItemMatches(item, props.searchQuery ?? '', itemPath(index)))
})
```

模板在搜索时不要用 draggable 直接绑定 filtered list,否则 index 和真实数组错位。推荐:

```vue
<template v-if="isSearching">
  <el-card v-for="{ item, index } in visibleItems" :key="localKeys[index]">...</el-card>
</template>
<draggable v-else :list="localItems" ...>...</draggable>
```

为避免双写模板,可以抽一个内部 `renderItem` 不现实；Vue SFC 中可接受少量重复,或把 item card 抽成 `ItemCard` 局部组件(不建议本轮增加新文件)。

在搜索模式:
- 隐藏 drag handle 或显示但禁用 cursor
- 删除/编辑仍按真实 index 调用 `onDelete(index)` / `onItemFieldUpdate(index, newItem)`
- `NestedObject` 传 `field-path="itemPath(index)"` 和 `search-query`

空结果:

```vue
<div v-if="isSearching && visibleItems.length === 0" class="empty-hint">未找到匹配物品</div>
```

### Step 5:递归匹配细节

`listItemMatches(item, query, path)`:
- 若 item 是 dict:
  - 匹配 `item.__className` 的 itemSearchParts
  - 遍历每个 key:
    - fieldSearchParts(`${path}.${key}`, key, value)
    - 若 leaf,也匹配 value 文本(例如某字符串枚举)
    - 若 dict/list,递归
- 若 item 非 dict:匹配 `String(item)`

`objectMatchesQuery(value, query, path)`:
- 类似 list item,但输入是 dict
- 不能修改 value
- 注意 circular 不会出现在 bundle JSON,无需 visited set

### Step 6:测试

#### Unit/pytest

如前端逻辑不方便 Python 单测,可以只做 e2e。本项目当前前端没有 vitest,不要引入新依赖。

#### Playwright e2e

新增 `tools/save-editor/tests/e2e/test_form_search.py`,沿用现有风格:

- URL `http://127.0.0.1:5010/`
- fixture 使用 `tools/save-editor/tests/fixtures/sample_save_with_cloth_armor.zip`
- 上传后切到"表单 Schema"
- 断言初始 `.field-row` 数量 > 0、`.item-card` 数量 > 0
- 搜 `数量`:
  - 可见 label 包含 `数量(quantity)`
  - 不应显示明显无关字段如 `诅咒(cursed)`(如果同层有)
- 清空,搜 `quantity`:
  - 同样命中数量字段
- 清空,搜 `布甲`:
  - `.item-title` 或 collapse/header 中包含 `布甲`
  - 与布甲 item 相关字段仍可见
- 清空,搜 `cloth`:
  - 命中 `ClothArmor`
- 清空,搜 `魔弹`:
  - nested collapse header 包含 `魔弹法杖`
- 搜一个不存在字符串 `zz-no-match-中文`:
  - 页面显示 `未找到匹配`
- 截图保存 `/tmp/form-search-shot.png`

worker 需要启动服务:

```bash
cd tools/save-editor/frontend && npm run build
cd .. && PORT=5010 python app.py
/tmp/spd-venv/bin/python tools/save-editor/tests/e2e/test_form_search.py
```

如果 5010 占用,改测试 URL 或临时环境变量。不要把端口硬编码改坏已有测试。

### Step 7:验证 build/test

必须执行:

```bash
cd tools/save-editor/frontend && npm run build
/tmp/spd-venv/bin/pytest tools/save-editor/tests -q
```

并执行新增 e2e。

### Step 8:代码评审

按 dispatcher 流程,worker 完成后让 codex_reviewer 做代码评审。重点看:
- filtered list 的 index 是否仍指向真实 `localItems` index(删除/编辑不串位)
- 搜索模式是否禁用拖拽,避免 filtered list reorder 导致数据错乱
- 递归匹配是否同时覆盖中文字段名、英文 key、中文 item 名、英文 class 名
- 搜索清空后数据和展开状态是否不被破坏
- mobile viewport 下搜索框和过滤结果是否仍可用

## Acceptance

### 功能验收

- [x] 表单 Schema 页出现搜索框,placeholder 明确说明支持中英文/class 名
- [x] 搜 `数量` 能显示 `quantity` 字段
- [x] 搜 `quantity` 能显示同一字段
- [x] 搜 `布甲` 能显示 `ClothArmor`/布甲 item
- [x] 搜 `cloth` 能显示同一 item
- [x] 搜 `魔弹` 能显示 `WandOfMagicMissile`/魔弹法杖相关 nested 对象
- [x] 搜不存在字符串显示空结果提示
- [x] 清空搜索后恢复完整表单
- [x] 搜索模式下编辑字段仍能写入 store,下载 zip 不受影响
- [x] 搜索模式下不允许拖拽排序(或拖拽不会改变真实数组)

### 自动验证

- [x] `npm run build` passed
- [x] `/tmp/spd-venv/bin/pytest tools/save-editor/tests -q` passed
- [x] `tools/save-editor/tests/e2e/test_form_search.py` passed

### 不退化检查

- [x] NumericFields tab 不受影响
- [x] RawJsonEditor 不受影响
- [x] item label `布甲(ClothArmor)` 仍显示
- [x] mobile 375px viewport 下搜索框宽度正常,字段值仍可见

## Notes

- 不要引入 Fuse.js 等依赖;本轮 substring fuzzy 足够
- 不要把搜索结果写入 Pinia store;仅组件 computed 过滤
- 不要为搜索模式复制/重排真实数组
- 不要改 Bundle 数据结构或 `__className`
