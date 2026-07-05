import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { SaveSlotBundle, GameBundle, SlotMeta, BundleDict } from '@/types'

interface BundleState {
  meta: SlotMeta
  game: GameBundle
  depths: Record<string, BundleDict>
  __raw_files: Record<string, BundleDict>
  warnings: string[]
}

function clone<T>(v: T): T {
  if (typeof structuredClone === 'function') {
    try {
      return structuredClone(v)
    } catch {
      return JSON.parse(JSON.stringify(v))
    }
  }
  return JSON.parse(JSON.stringify(v))
}

export const useBundleStore = defineStore('bundle', () => {
  const bundle = ref<BundleState | null>(null)
  const originalBundle = ref<BundleState | null>(null)
  const dirty = ref(false)

  const hasBundle = computed(() => bundle.value !== null)
  const hero = computed(() => bundle.value?.game?.hero ?? null)
  const meta = computed(() => bundle.value?.meta ?? null)
  const game = computed(() => bundle.value?.game ?? null)

  function setBundle(b: SaveSlotBundle) {
    const state: BundleState = {
      meta: b.meta ?? {},
      game: b.game as GameBundle,
      depths: b.depths ?? {},
      __raw_files: b.__raw_files ?? {},
      warnings: b.warnings ?? [],
    }
    bundle.value = state
    originalBundle.value = clone(state)
    dirty.value = false
  }

  function setHeroField<K extends keyof GameBundle['hero']>(
    key: K,
    value: GameBundle['hero'][K]
  ) {
    if (!bundle.value) return
    bundle.value.game.hero[key] = value
    dirty.value = true
  }

  function replaceHero(hero: GameBundle['hero']) {
    if (!bundle.value) return
    bundle.value.game.hero = hero
    dirty.value = true
  }

  function replaceGame(game: GameBundle) {
    if (!bundle.value) return
    bundle.value.game = game
    dirty.value = true
  }

  function setMetaField<K extends keyof SlotMeta>(key: K, value: SlotMeta[K]) {
    if (!bundle.value) return
    bundle.value.meta[key] = value
    dirty.value = true
  }

  function resetToOriginal() {
    if (!originalBundle.value) return
    bundle.value = clone(originalBundle.value)
    dirty.value = false
  }

  function clearBundle() {
    bundle.value = null
    originalBundle.value = null
    dirty.value = false
  }

  function markDirty() {
    dirty.value = true
  }

  function clearDirty() {
    dirty.value = false
  }

  return {
    bundle,
    originalBundle,
    dirty,
    hasBundle,
    hero,
    meta,
    game,
    setBundle,
    setHeroField,
    replaceHero,
    replaceGame,
    setMetaField,
    resetToOriginal,
    clearBundle,
    markDirty,
    clearDirty,
    clone,
  }
})
