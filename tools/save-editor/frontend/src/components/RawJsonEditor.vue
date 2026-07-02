<template>
  <el-card class="section-card">
    <template #header>
      <span>Raw JSON 编辑器(显式"应用"写入 store)</span>
    </template>

    <el-tabs v-model="active">
      <el-tab-pane v-for="f in fields" :key="f.key" :label="f.label" :name="f.key">
        <div class="toolbar">
          <el-button size="small" type="primary" @click="applyField(f.key)">
            应用
          </el-button>
          <el-button size="small" @click="formatField(f.key)">格式化</el-button>
          <el-button size="small" @click="validateField(f.key)">校验</el-button>
          <span class="msg" :class="msgType">{{ msg[f.key] }}</span>
        </div>

        <div class="monaco-wrapper">
          <vue-monaco-editor
            :value="texts[f.key]"
            @update:value="(v: string) => onTextChange(f.key, v)"
            @mount="(ed: any, monaco: any) => onMount(f.key, ed, monaco)"
            theme="vs-dark"
            language="json"
            :options="{
              minimap: { enabled: false },
              fontSize: 13,
              tabSize: 2,
              automaticLayout: true,
              scrollBeyondLastLine: false,
            }"
            :load-language-from-vscode-nls="false"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { ref, watch, reactive, defineAsyncComponent } from 'vue'
import { ElMessage } from 'element-plus'
import { useBundleStore } from '@/stores/bundle'
import { useHistoryStore } from '@/stores/history'
import type { Item } from '@/types'

const vueMonacoEditor = defineAsyncComponent(() =>
  import('@guolao/vue-monaco-editor').then((m) => m.VueMonacoEditor)
)

const bundleStore = useBundleStore()
const historyStore = useHistoryStore()

const fields = [
  { key: 'armor', label: 'hero.armor' },
  { key: 'weapon', label: 'hero.weapon' },
  { key: 'inventory', label: 'hero.inventory' },
] as const

type FieldKey = (typeof fields)[number]['key']

const active = ref<FieldKey>('armor')

const texts = reactive<Record<FieldKey, string>>({
  armor: '',
  weapon: '',
  inventory: '',
})

const msg = reactive<Record<FieldKey, string>>({
  armor: '',
  weapon: '',
  inventory: '',
})

const msgType = ref<'' | 'error' | 'ok'>('')

const isEditing = reactive<Record<FieldKey, boolean>>({
  armor: false,
  weapon: false,
  inventory: false,
})

// Monaco editor instances(for format action) + blur handlers
const editors = reactive<Record<string, any>>({})

function onMount(key: FieldKey, ed: any, _monaco: any) {
  editors[key] = ed
  // Mount 只 store editor instance,不设 isEditing。
  // 监听 focus/blur 维护 isEditing(只表示"用户当前正聚焦编辑"),
  // 这样表单字段写 store 时若 Monaco 未 focus,会自动同步 text。
  ed.onDidFocusEditorText?.(() => {
    isEditing[key] = true
  })
  ed.onDidBlurEditorText?.(() => {
    isEditing[key] = false
  })
}

function onTextChange(key: FieldKey, v: string) {
  texts[key] = v
  isEditing[key] = true
}

function readRaw(key: FieldKey): unknown | null {
  const hero = bundleStore.hero
  if (!hero) return null
  if (key === 'armor') return hero.armor
  if (key === 'weapon') return hero.weapon
  if (key === 'inventory') return hero.inventory
  return null
}

function writeRaw(key: FieldKey, value: unknown): boolean {
  if (!bundleStore.hero) return false
  if (key === 'armor') {
    bundleStore.setHeroField('armor', value as Item | null)
  } else if (key === 'weapon') {
    bundleStore.setHeroField('weapon', value as Item | null)
  } else if (key === 'inventory') {
    bundleStore.setHeroField('inventory', value as Item[])
  } else {
    return false
  }
  return true
}

function resyncTexts() {
  for (const f of fields) {
    if (isEditing[f.key]) continue
    const raw = readRaw(f.key)
    texts[f.key] = raw === null ? 'null' : JSON.stringify(raw, null, 2)
  }
}

watch(
  () => bundleStore.hero,
  () => resyncTexts(),
  { deep: true, immediate: true }
)

function applyField(key: FieldKey) {
  msgType.value = ''
  msg[key] = ''
  let parsed: unknown
  try {
    parsed = JSON.parse(texts[key])
  } catch (e: any) {
    msgType.value = 'error'
    msg[key] = 'JSON 解析失败: ' + e.message
    ElMessage.error(`字段 ${key} 应用失败:${e.message}`)
    return
  }

  // 类型校验 — 与旧 templates/index.html:validateItemType 一致:
  // - armor: 拒 null,必须是 dict
  // - weapon: 允许 dict 或 null
  // - inventory: 必须是 Array
  if (key === 'armor') {
    if (parsed === null) {
      msgType.value = 'error'
      msg[key] = 'hero.armor 不能为 null(用空对象或保留原 armor)'
      ElMessage.error(msg[key])
      return
    }
    if (typeof parsed !== 'object' || Array.isArray(parsed)) {
      msgType.value = 'error'
      const got = Array.isArray(parsed) ? 'Array' : typeof parsed
      msg[key] = `hero.armor 必须是 JSON 对象(dict),收到 ${got}`
      ElMessage.error(msg[key])
      return
    }
  } else if (key === 'weapon') {
    if (parsed !== null && (typeof parsed !== 'object' || Array.isArray(parsed))) {
      msgType.value = 'error'
      const got = Array.isArray(parsed) ? 'Array' : typeof parsed
      msg[key] = `hero.weapon 必须是 JSON 对象(dict)或 null,收到 ${got}`
      ElMessage.error(msg[key])
      return
    }
  } else if (key === 'inventory') {
    if (!Array.isArray(parsed)) {
      msgType.value = 'error'
      msg[key] = `hero.inventory 必须是 JSON 数组(Array),收到 ${typeof parsed}`
      ElMessage.error(msg[key])
      return
    }
  }

  // __className 删除检测 — 与旧版 applyItemField 一致:
  // 递归收集 original 中所有 __className 路径,若 parsed 在对应路径
  // 不再有 __className(被删字段或整段 dict 被替换为非 dict),拒绝应用。
  // 这类 zip 游戏加载会崩,严格模式直接 reject(不让用户强行)。
  const original = readRaw(key)
  const before: { path: (string | number)[]; value: string }[] = []
  if (original !== undefined && original !== null) {
    collectClassNames(original, [], before)
  }
  const removed: { path: string; value: string }[] = []
  for (const entry of before) {
    if (!pathExistsWithClassName(parsed, entry.path)) {
      removed.push({ path: formatPath(entry.path), value: entry.value })
    }
  }
  if (removed.length > 0) {
    msgType.value = 'error'
    const list = removed.map((r) => `  - ${r.path} (${r.value})`).join('\n')
    msg[key] = `检测到 __className 删除,拒绝应用:\n${list}`
    ElMessage.error({
      message: `字段 ${key} 检测到 ${removed.length} 处 __className 删除,已拒绝应用(检查 msg 区域)`,
      duration: 5000,
    })
    return
  }

  historyStore.pushSnapshot()
  writeRaw(key, parsed)
  isEditing[key] = false
  msgType.value = 'ok'
  msg[key] = `已应用 ${key} → store`
  ElMessage.success(`已应用 ${key}`)
}

/**
 * 递归收集所有 dict(含 root)的 __className 路径。
 * 与旧 templates/index.html:collectClassNames 等价。
 */
function collectClassNames(
  obj: unknown,
  basePath: (string | number)[],
  out: { path: (string | number)[]; value: string }[]
) {
  if (Array.isArray(obj)) {
    for (let i = 0; i < obj.length; i++) {
      if (obj[i] && typeof obj[i] === 'object') {
        collectClassNames(obj[i], basePath.concat([i]), out)
      }
    }
    return
  }
  if (obj && typeof obj === 'object') {
    const o = obj as Record<string, unknown>
    if ('__className' in o && typeof o.__className === 'string') {
      out.push({ path: basePath.slice(), value: o.__className })
    }
    for (const k of Object.keys(o)) {
      const child = o[k]
      if (child && typeof child === 'object') {
        collectClassNames(child, basePath.concat([k]), out)
      }
    }
  }
}

/**
 * parsed 中对应 path 处是否仍是 dict 且带 string __className。
 */
function pathExistsWithClassName(
  parsed: unknown,
  path: (string | number)[]
): boolean {
  let cur: unknown = parsed
  for (const p of path) {
    if (cur === null || typeof cur !== 'object') return false
    cur = (cur as Record<string | number, unknown>)[p as any]
  }
  return (
    cur !== null &&
    typeof cur === 'object' &&
    !Array.isArray(cur) &&
    '__className' in (cur as object) &&
    typeof (cur as Record<string, unknown>).__className === 'string'
  )
}

function formatPath(path: (string | number)[]): string {
  if (path.length === 0) return '(root)'
  let out = ''
  for (let i = 0; i < path.length; i++) {
    const seg = path[i]
    if (typeof seg === 'number' || /^\d+$/.test(String(seg))) {
      out += `[${seg}]`
    } else {
      out += (i === 0 ? '' : '.') + seg
    }
  }
  return out
}

function formatField(key: FieldKey) {
  try {
    const parsed = JSON.parse(texts[key])
    texts[key] = JSON.stringify(parsed, null, 2)
    msgType.value = 'ok'
    msg[key] = '格式化成功'
  } catch (e: any) {
    msgType.value = 'error'
    msg[key] = '格式化失败: ' + e.message
  }
}

function validateField(key: FieldKey) {
  try {
    JSON.parse(texts[key])
    msgType.value = 'ok'
    msg[key] = 'JSON 语法正确'
  } catch (e: any) {
    msgType.value = 'error'
    msg[key] = '语法错误: ' + e.message
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.msg {
  font-size: 0.85em;
  font-family: monospace;
}
.msg.error {
  color: var(--el-color-danger);
}
.msg.ok {
  color: var(--el-color-success);
}

@media (max-width: 768px) {
  .toolbar {
    flex-wrap: wrap;
    gap: 4px;
  }
  .monaco-wrapper {
    height: 240px;
  }
}
</style>
