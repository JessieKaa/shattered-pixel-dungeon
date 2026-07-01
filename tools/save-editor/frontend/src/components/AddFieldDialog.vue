<template>
  <div class="add-field">
    <el-dialog
      v-model="visible"
      title="添加字段"
      width="420px"
      :close-on-click-modal="false"
    >
      <el-form :model="form" :rules="rules" ref="formRef" label-width="80px">
        <el-form-item label="字段名" prop="name">
          <el-input
            v-model="form.name"
            placeholder="字母/_ 开头,只含字母数字下划线"
            @keyup.enter="onConfirm"
          />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" placeholder="选择类型">
            <el-option
              v-for="t in FIELD_TYPES"
              :key="t"
              :label="t"
              :value="t"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="默认值">
          <span class="hint">{{ previewDefault() }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="onConfirm">添加</el-button>
      </template>
    </el-dialog>

    <el-button size="small" type="primary" plain @click="open">+ 添加字段</el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { FIELD_TYPES, type FieldType } from '@/types'
import { isValidFieldName, defaultValueFor } from '@/composables/useFieldType'

const props = defineProps<{
  existingKeys: string[]
}>()

const emit = defineEmits<{
  (e: 'add', name: string, type: FieldType): void
}>()

const visible = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  name: '',
  type: 'int' as FieldType,
})

const rules: FormRules = {
  name: [
    { required: true, message: '请输入字段名', trigger: 'blur' },
    {
      validator: (_r, value: string, cb) => {
        if (!value) return cb()
        if (!isValidFieldName(value)) {
          return cb(new Error('需匹配 /^[a-zA-Z_][a-zA-Z0-9_]*$/'))
        }
        if (props.existingKeys.includes(value)) {
          return cb(new Error('字段已存在,拒绝重名'))
        }
        cb()
      },
      trigger: 'blur',
    },
  ],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
}

function open() {
  form.name = ''
  form.type = 'int'
  visible.value = true
}

function previewDefault(): string {
  return JSON.stringify(defaultValueFor(form.type))
}

async function onConfirm() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  emit('add', form.name.trim(), form.type)
  ElMessage.success(`已添加字段 ${form.name}`)
  visible.value = false
}
</script>

<style scoped>
.hint {
  color: var(--el-text-color-secondary);
  font-family: monospace;
}
</style>
