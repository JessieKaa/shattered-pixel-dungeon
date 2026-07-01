<template>
  <el-card class="section-card">
    <template #header>
      <span>存档信息</span>
    </template>
    <el-descriptions :column="3" border>
      <el-descriptions-item label="meta.name">
        {{ bundleStore.meta?.name ?? '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="meta.depth">
        {{ bundleStore.meta?.depth ?? '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="meta.level">
        {{ bundleStore.meta?.level ?? '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="meta.hero_class">
        {{ bundleStore.meta?.hero_class ?? '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="meta.version">
        {{ bundleStore.meta?.version ?? '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="dirty">
        <el-tag :type="bundleStore.dirty ? 'warning' : 'success'" size="small">
          {{ bundleStore.dirty ? '已修改' : '未改动' }}
        </el-tag>
      </el-descriptions-item>
    </el-descriptions>

    <el-alert
      v-for="w in bundleStore.bundle?.warnings ?? []"
      :key="w"
      :title="w"
      type="warning"
      :closable="false"
      show-icon
      style="margin-top: 12px"
    />
  </el-card>
</template>

<script setup lang="ts">
import { useBundleStore } from '@/stores/bundle'
const bundleStore = useBundleStore()
</script>
