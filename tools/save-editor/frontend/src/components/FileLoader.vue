<template>
  <el-card class="section-card">
    <template #header>
      <span>1. 上传存档</span>
    </template>
    <el-upload
      drag
      :auto-upload="true"
      :show-file-list="false"
      :http-request="handleUpload"
      accept=".zip,.dat,.bundle"
    >
      <el-icon class="el-icon--upload"><upload-filled /></el-icon>
      <div class="el-upload__text">
        拖拽 zip 到这里,或<em>点击选择</em>
      </div>
      <template #tip>
        <div class="el-upload__tip">
          支持 .zip(slot zip)/ .dat(单 bundle)/ .bundle
        </div>
      </template>
    </el-upload>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      style="margin-top: 12px"
    />
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { parseZip } from '@/api'
import { useBundleStore } from '@/stores/bundle'
import { useHistoryStore } from '@/stores/history'

const bundleStore = useBundleStore()
const historyStore = useHistoryStore()
const error = ref('')

async function handleUpload(opts: UploadRequestOptions) {
  error.value = ''
  const file = opts.file as File
  if (!file) return
  try {
    const data = await parseZip(file)
    bundleStore.setBundle(data)
    historyStore.clear()
    historyStore.pushSnapshot()
    ElMessage.success(`已加载:${data.meta?.name ?? '(unnamed)'}`)
  } catch (e: any) {
    error.value = e?.message || String(e)
    ElMessage.error(error.value)
  }
}
</script>
