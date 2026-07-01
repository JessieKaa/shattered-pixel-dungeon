<template>
  <el-card class="section-card">
    <template #header>
      <span>表单 Schema 编辑器(armor / weapon / inventory)</span>
    </template>

    <el-tabs v-model="active">
      <el-tab-pane label="护甲 (hero.armor)" name="armor">
        <template v-if="hasArmor">
          <NestedObject
            :value="armor"
            @update="onArmorUpdate"
            @delete="onArmorDelete"
          />
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
          <NestedObject
            :value="weapon"
            @update="onWeaponUpdate"
            @delete="onWeaponDelete"
          />
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

function onArmorUpdate(key: string, val: unknown) {
  if (!bundleStore.hero?.armor) return
  const next = { ...bundleStore.hero.armor, [key]: val }
  bundleStore.setHeroField('armor', next as any)
}

function onArmorDelete(key: string) {
  if (!bundleStore.hero?.armor) return
  historyStore.pushSnapshot()
  const next = { ...bundleStore.hero.armor }
  delete (next as any)[key]
  bundleStore.setHeroField('armor', next as any)
}

function onWeaponUpdate(key: string, val: unknown) {
  if (!bundleStore.hero?.weapon) return
  const next = { ...bundleStore.hero.weapon, [key]: val }
  bundleStore.setHeroField('weapon', next as any)
}

function onWeaponDelete(key: string) {
  if (!bundleStore.hero?.weapon) return
  historyStore.pushSnapshot()
  const next = { ...bundleStore.hero.weapon }
  delete (next as any)[key]
  bundleStore.setHeroField('weapon', next as any)
}

function onInventoryUpdate(val: unknown[]) {
  bundleStore.setHeroField('inventory', val as any)
}

function onCreateArmor() {
  historyStore.pushSnapshot()
  bundleStore.setHeroField('armor', ITEM_TEMPLATES.ClothArmor() as any)
}

function onCreateWeapon() {
  historyStore.pushSnapshot()
  bundleStore.setHeroField('weapon', ITEM_TEMPLATES.Dagger() as any)
}
</script>
