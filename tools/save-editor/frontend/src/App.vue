<template>
  <el-config-provider :locale="zhCn">
    <div class="app-container">
      <header class="app-header">
        <h1>SPD Save Slot Editor</h1>
        <div class="subtle">
          本地工具 · 上传 slot zip → 改字段 → 下载新 zip → 游戏内 import
        </div>
      </header>

      <FileLoader v-if="!bundleStore.bundle" />
      <template v-else>
        <ActionBar />
        <SlotMetaPanel />
        <el-tabs v-model="uiStore.activeTab" class="section-card">
          <el-tab-pane label="数值字段" name="numeric">
            <NumericFields />
          </el-tab-pane>
          <el-tab-pane label="表单 Schema" name="form">
            <FormSchemaEditor />
          </el-tab-pane>
          <el-tab-pane label="Raw JSON" name="raw">
            <RawJsonEditor />
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { provide, watch, onMounted, onUnmounted } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import FileLoader from './components/FileLoader.vue'
import ActionBar from './components/ActionBar.vue'
import SlotMetaPanel from './components/SlotMetaPanel.vue'
import NumericFields from './components/NumericFields.vue'
import FormSchemaEditor from './components/FormSchemaEditor.vue'
import RawJsonEditor from './components/RawJsonEditor.vue'
import { useBundleStore } from './stores/bundle'
import { useHistoryStore } from './stores/history'
import { useUiStore } from './stores/ui'

const bundleStore = useBundleStore()
const historyStore = useHistoryStore()
const uiStore = useUiStore()

provide('history', historyStore)

// Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y 快捷键
function onKey(e: KeyboardEvent) {
  if (!(e.ctrlKey || e.metaKey)) return
  const key = e.key.toLowerCase()
  if (key === 'z' && !e.shiftKey) {
    e.preventDefault()
    historyStore.undo()
  } else if ((key === 'z' && e.shiftKey) || key === 'y') {
    e.preventDefault()
    historyStore.redo()
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))

// 监听 game 变化 → mark dirty
watch(
  () => bundleStore.bundle?.game,
  (g) => {
    if (g && !historyStore.isApplying) {
      bundleStore.markDirty()
    }
  },
  { deep: true }
)
</script>
