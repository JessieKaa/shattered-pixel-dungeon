<template>
  <div class="add-item">
    <el-dialog
      v-model="visible"
      title="添加 list item"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px">
        <el-form-item label="__className">
          <el-select
            v-model="selected"
            placeholder="选择常用 Item 模板"
            filterable
            allow-create
            @change="onSelectChange"
          >
            <el-option
              v-for="key in templateKeys"
              :key="key"
              :label="key"
              :value="key"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="默认值预览">
          <pre class="preview">{{ preview }}</pre>
        </el-form-item>
        <el-form-item label="自定义 JSON">
          <el-input
            v-model="customJson"
            type="textarea"
            :rows="6"
            placeholder='留空则使用模板默认值;或粘贴完整 JSON,如 {"__className":"...","quantity":1}'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="onConfirm">添加</el-button>
      </template>
    </el-dialog>

    <el-button size="small" type="primary" plain @click="open">+ 添加 item</el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { ITEM_TEMPLATES } from '@/composables/itemTemplates'

const emit = defineEmits<{
  (e: 'add', item: Record<string, unknown>): void
}>()

const visible = ref(false)
const selected = ref<string>('')
const customJson = ref<string>('')

const templateKeys = computed(() => Object.keys(ITEM_TEMPLATES))

const preview = computed(() => {
  if (selected.value && ITEM_TEMPLATES[selected.value]) {
    return JSON.stringify(ITEM_TEMPLATES[selected.value](), null, 2)
  }
  return '(未选择模板)'
})

function onSelectChange() {
  // 同步预览
}

function open() {
  selected.value = 'Food'
  customJson.value = ''
  visible.value = true
}

function onConfirm() {
  let item: Record<string, unknown>
  if (customJson.value.trim()) {
    try {
      const parsed = JSON.parse(customJson.value)
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        ElMessage.error('JSON 必须是对象')
        return
      }
      item = parsed as Record<string, unknown>
    } catch (e: any) {
      ElMessage.error('JSON 解析失败: ' + e.message)
      return
    }
  } else if (selected.value && ITEM_TEMPLATES[selected.value]) {
    item = ITEM_TEMPLATES[selected.value]()
  } else {
    ElMessage.error('请选择模板或输入自定义 JSON')
    return
  }
  if (!item.__className || typeof item.__className !== 'string') {
    ElMessage.error('item 必须包含字符串 __className')
    return
  }
  emit('add', item)
  ElMessage.success('已添加 item')
  visible.value = false
}
</script>

<style scoped>
.preview {
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  font-size: 0.85em;
  max-height: 180px;
  overflow: auto;
  margin: 0;
}
</style>
