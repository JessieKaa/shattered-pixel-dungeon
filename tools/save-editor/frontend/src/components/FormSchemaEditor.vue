<template>
  <el-card class="section-card">
    <template #header>
      <span>表单 Schema 编辑器(armor / weapon / inventory)</span>
    </template>

    <el-tabs v-model="active">
      <el-tab-pane label="护甲 (hero.armor)" name="armor">
        <template v-if="hasArmor">
          <NestedObject :value="armor" @update="onArmorUpdate" />
        </template>
        <template v-else>
          <el-empty description="未装备护甲" :image-size="60">
            <el-button type="primary" plain @click="onCreateArmor">
              + 装备 ClothArmor
            </el-button>
          </el-empty>
        </template>
      </el-tab-pane>

      <el-tab-pane label="武器 (hero.weapon)" name="weapon">
        <template v-if="hasWeapon">
          <NestedObject :value="weapon" @update="onWeaponUpdate" />
        </template>
        <template v-else>
          <el-empty description="未装备武器" :image-size="60">
            <el-button type="primary" plain @click="onCreateWeapon">
              + 装备 Dagger
            </el-button>
          </el-empty>
        </template>
      </el-tab-pane>

      <el-tab-pane label="背包 (hero.inventory)" name="inventory">
        <NestedList
          :value="inventory"
          field-key="inventory"
          @update="onInventoryUpdate"
        />
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import NestedObject from './NestedObject.vue'
import NestedList from './NestedList.vue'
import { useBundleStore } from '@/stores/bundle'
import { useHistoryStore } from '@/stores/history'
import { ITEM_TEMPLATES } from '@/composables/itemTemplates'

const bundleStore = useBundleStore()
const historyStore = useHistoryStore()
const active = ref<'armor' | 'weapon' | 'inventory'>('armor')

const armor = computed(() => bundleStore.hero?.armor ?? null)
const weapon = computed(() => bundleStore.hero?.weapon ?? null)
const inventory = computed(() => bundleStore.hero?.inventory ?? [])
const hasArmor = computed(() => !!armor.value)
const hasWeapon = computed(() => !!weapon.value)

/**
 * 表单字段编辑:用 beginEdit() 1s transaction 合并连续 input,
 * 写入 store 前 capture pre-change snapshot。
 */
function onArmorUpdate(newArmor: Record<string, unknown>) {
  if (!bundleStore.hero) return
  historyStore.beginEdit()
  bundleStore.setHeroField('armor', newArmor as any)
}

function onWeaponUpdate(newWeapon: Record<string, unknown>) {
  if (!bundleStore.hero) return
  historyStore.beginEdit()
  bundleStore.setHeroField('weapon', newWeapon as any)
}

function onInventoryUpdate(newInv: unknown[]) {
  if (!bundleStore.hero) return
  historyStore.beginEdit()
  bundleStore.setHeroField('inventory', newInv as any)
}

function onCreateArmor() {
  // 创建/删除属于"非连续编辑"操作,用 pushSnapshot 直接 capture pre-change
  historyStore.pushSnapshot()
  bundleStore.setHeroField('armor', ITEM_TEMPLATES.ClothArmor() as any)
}

function onCreateWeapon() {
  historyStore.pushSnapshot()
  bundleStore.setHeroField('weapon', ITEM_TEMPLATES.Dagger() as any)
}
</script>
