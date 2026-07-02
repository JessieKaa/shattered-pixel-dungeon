<template>
  <el-card class="section-card">
    <template #header>
      <span>2. 数值字段编辑(15 项)</span>
    </template>

    <el-collapse v-model="activeGroups">
      <el-collapse-item title="meta(5 字段)" name="meta">
        <el-form label-width="160px">
          <el-form-item label="meta.name">
            <template #label>
              <FieldLabel path="meta.name" />
            </template>
            <el-input
              :model-value="String(bundleStore.meta?.name ?? '')"
              @update:model-value="(v: any) => update('meta', 'name', v)"
              placeholder="存档名"
            />
          </el-form-item>
          <el-form-item label="meta.depth">
            <template #label>
              <FieldLabel path="meta.depth" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.meta?.depth)"
              @update:model-value="(v: any) => update('meta', 'depth', v)"
              :min="1"
              :max="30"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="meta.level">
            <template #label>
              <FieldLabel path="meta.level" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.meta?.level)"
              @update:model-value="(v: any) => update('meta', 'level', v)"
              :min="1"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="meta.hero_class">
            <template #label>
              <FieldLabel path="meta.hero_class" />
            </template>
            <el-select
              :model-value="String(bundleStore.meta?.hero_class ?? 'WARRIOR')"
              @update:model-value="(v: any) => update('meta', 'hero_class', v)"
              placeholder="职业"
            >
              <el-option v-for="c in HERO_CLASSES" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="meta.version">
            <template #label>
              <FieldLabel path="meta.version" />
            </template>
            <el-input
              :model-value="String(bundleStore.meta?.version ?? '')"
              disabled
            />
            <div class="hint">只读 · pack 时默认 force 到 896</div>
          </el-form-item>
        </el-form>
      </el-collapse-item>

      <el-collapse-item title="game.hero(6 字段)" name="hero">
        <el-form label-width="160px">
          <el-form-item label="hero.HP">
            <template #label>
              <FieldLabel path="hero.HP" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.hero?.HP)"
              @update:model-value="(v: any) => update('hero', 'HP', v)"
              :min="0"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="hero.HT">
            <template #label>
              <FieldLabel path="hero.HT" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.hero?.HT)"
              @update:model-value="(v: any) => update('hero', 'HT', v)"
              :min="1"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="hero.pos">
            <template #label>
              <FieldLabel path="hero.pos" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.hero?.pos)"
              @update:model-value="(v: any) => update('hero', 'pos', v)"
              :min="0"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="hero.lvl">
            <template #label>
              <FieldLabel path="hero.lvl" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.hero?.lvl)"
              @update:model-value="(v: any) => update('hero', 'lvl', v)"
              :min="1"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="hero.STR">
            <template #label>
              <FieldLabel path="hero.STR" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.hero?.STR)"
              @update:model-value="(v: any) => update('hero', 'STR', v)"
              :min="0"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="hero.exp">
            <template #label>
              <FieldLabel path="hero.exp" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.hero?.exp)"
              @update:model-value="(v: any) => update('hero', 'exp', v)"
              :min="0"
              controls-position="right"
            />
          </el-form-item>
        </el-form>
      </el-collapse-item>

      <el-collapse-item title="game 顶层(4 字段)" name="game">
        <el-form label-width="160px">
          <el-form-item label="game.gold">
            <template #label>
              <FieldLabel path="game.gold" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.game?.gold)"
              @update:model-value="(v: any) => update('game', 'gold', v)"
              :min="0"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="game.challenges">
            <template #label>
              <FieldLabel path="game.challenges" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.game?.challenges)"
              @update:model-value="(v: any) => update('game', 'challenges', v)"
              :min="0"
              controls-position="right"
            />
            <div class="hint">bitmask · 多挑战位组合</div>
          </el-form-item>
          <el-form-item label="game.seed">
            <template #label>
              <FieldLabel path="game.seed" />
            </template>
            <el-input-number
              :model-value="num(bundleStore.game?.seed)"
              @update:model-value="(v: any) => update('game', 'seed', v)"
              controls-position="right"
            />
            <div class="hint warn">改 seed 不删 depth 会出怪</div>
          </el-form-item>
          <el-form-item label="game.daily">
            <template #label>
              <FieldLabel path="game.daily" />
            </template>
            <el-switch
              :model-value="!!bundleStore.game?.daily"
              @update:model-value="(v: any) => update('game', 'daily', v)"
            />
            <div class="hint warn">daily=true 会被 isSaveAllowed 拒绝存读档</div>
          </el-form-item>
        </el-form>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useBundleStore } from '@/stores/bundle'
import { useHistoryStore } from '@/stores/history'
import { HERO_CLASSES } from '@/types'
import FieldLabel from './FieldLabel.vue'

const bundleStore = useBundleStore()
const historyStore = useHistoryStore()

const activeGroups = ref(['meta', 'hero', 'game'])

function num(v: unknown): number {
  if (typeof v === 'number') return v
  if (typeof v === 'string' && v !== '') return Number(v)
  return 0
}

let debounceTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 表单字段编辑事务:第一次修改前 pushSnapshot 捕获 pre-change,
 * 1s 内的连续修改共用一个 transaction(只在第一次 capture 入栈)。
 * history.beginEdit 内部处理 transaction 状态。
 */
function beginEditTransaction() {
  historyStore.beginEdit()
}

function update(group: 'meta' | 'hero' | 'game', key: string, value: unknown) {
  if (!bundleStore.bundle) return
  beginEditTransaction()
  if (group === 'meta') {
    bundleStore.setMetaField(key as any, value as any)
  } else if (group === 'hero') {
    bundleStore.setHeroField(key as any, value as any)
  } else if (group === 'game') {
    if (bundleStore.bundle.game) {
      ;(bundleStore.bundle.game as any)[key] = value
    }
  }
}
</script>

<style scoped>
.hint {
  color: var(--el-text-color-secondary);
  font-size: 0.8rem;
  margin-left: 8px;
}
.hint.warn {
  color: var(--el-color-warning);
}
</style>
