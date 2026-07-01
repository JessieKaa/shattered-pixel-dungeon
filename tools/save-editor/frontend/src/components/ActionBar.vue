<template>
  <el-card class="section-card">
    <div class="action-bar">
      <el-button-group>
        <el-button
          :icon="ArrowLeft"
          :disabled="!historyStore.canUndo"
          @click="historyStore.undo()"
        >
          撤销
        </el-button>
        <el-button
          :disabled="!historyStore.canRedo"
          @click="historyStore.redo()"
        >
          重做<el-icon class="el-icon--right"><ArrowRight /></el-icon>
        </el-button>
      </el-button-group>

      <span class="hint">
        undo 栈:{{ historyStore.undoStack.length }} /
        redo 栈:{{ historyStore.redoStack.length }}
        · Ctrl+Z / Ctrl+Shift+Z
      </span>

      <span class="spacer" />

      <el-tooltip content="重置到加载时状态" placement="top">
        <el-popconfirm
          title="确认重置所有改动?history 也会清空"
          @confirm="onReset"
          width="220"
        >
          <template #reference>
            <el-button type="warning" plain>重置</el-button>
          </template>
        </el-popconfirm>
      </el-tooltip>

      <el-checkbox v-model="forceVersion">force meta.version = 896</el-checkbox>

      <el-button type="primary" :loading="downloading" @click="onDownload">
        下载 zip
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { useBundleStore } from '@/stores/bundle'
import { useHistoryStore } from '@/stores/history'
import { packZip, buildPackRequest } from '@/api'

const bundleStore = useBundleStore()
const historyStore = useHistoryStore()
const downloading = ref(false)
const forceVersion = ref(true)

async function onDownload() {
  if (!bundleStore.bundle) return
  downloading.value = true
  try {
    const req = buildPackRequest(
      {
        meta: bundleStore.bundle.meta,
        game: bundleStore.bundle.game,
        depths: bundleStore.bundle.depths,
        __raw_files: bundleStore.bundle.__raw_files,
      },
      forceVersion.value ? 896 : null
    )
    const blob = await packZip(req)
    const name =
      (bundleStore.bundle.meta?.name as string) || 'slot'
    const filename = `${name}-edited.zip`
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    bundleStore.clearDirty()
    ElMessage.success(`已下载 ${filename}`)
  } catch (e: any) {
    ElMessage.error(e?.message || '下载失败')
  } finally {
    downloading.value = false
  }
}

function onReset() {
  bundleStore.resetToOriginal()
  historyStore.clear()
  ElMessage.success('已重置')
}
</script>

<style scoped>
.action-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 0.85em;
}
.spacer {
  flex: 1;
}
</style>
