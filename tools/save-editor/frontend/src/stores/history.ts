import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useBundleStore } from './bundle'
import type { GameBundle, SlotMeta } from '@/types'

const MAX_HISTORY = 50

export interface Snapshot {
  meta: SlotMeta
  game: GameBundle
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

export const useHistoryStore = defineStore('history', () => {
  const undoStack = ref<Snapshot[]>([])
  const redoStack = ref<Snapshot[]>([])
  // 防止 undo/redo 写回时 watch 重新触发 snapshot
  const isApplying = ref(false)

  // transaction 支持:首次修改前 capture pre-change 状态入栈,
  // 1s 内的连续修改只在 begin 时 push 一次。
  let pendingTimer: ReturnType<typeof setTimeout> | null = null
  let inTransaction = false

  const canUndo = computed(() => undoStack.value.length > 0)
  const canRedo = computed(() => redoStack.value.length > 0)

  function capture(): Snapshot | null {
    const bundleStore = useBundleStore()
    if (!bundleStore.bundle) return null
    return clone({
      meta: bundleStore.bundle.meta,
      game: bundleStore.bundle.game,
    })
  }

  function pushSnapshot() {
    if (isApplying.value) return
    const snap = capture()
    if (!snap) return
    undoStack.value.push(snap)
    if (undoStack.value.length > MAX_HISTORY) {
      undoStack.value.shift()
    }
    redoStack.value = []
  }

  /**
   * 表单字段编辑事务:第一次修改时 pushSnapshot (pre-change 状态在
   * 调用方写入前已经 capture — 但 caller 已经写过 store 一次了)。
   * 实际约定:beginEdit 在每次 input 前调用,内部检查 inTransaction,
   * 若不在 txn 内则 capture pre-change 入 undoStack,标记 inTransaction,
   * 启动 1s 定时器到时清 inTransaction。
   *
   * 注意:调用方必须在写入 store 之前调用 beginEdit()。
   */
  function beginEdit() {
    if (isApplying.value) return
    if (inTransaction) return
    pushSnapshot()
    inTransaction = true
    if (pendingTimer) clearTimeout(pendingTimer)
    pendingTimer = setTimeout(() => {
      inTransaction = false
      pendingTimer = null
    }, 1000)
  }

  function undo() {
    const bundleStore = useBundleStore()
    if (!bundleStore.bundle || undoStack.value.length === 0) return
    const current = capture()!
    const prev = undoStack.value.pop()!
    redoStack.value.push(current)
    isApplying.value = true
    bundleStore.bundle.meta = prev.meta
    bundleStore.replaceGame(prev.game)
    setTimeout(() => {
      isApplying.value = false
    }, 0)
  }

  function redo() {
    const bundleStore = useBundleStore()
    if (!bundleStore.bundle || redoStack.value.length === 0) return
    const current = capture()!
    const next = redoStack.value.pop()!
    undoStack.value.push(current)
    isApplying.value = true
    bundleStore.bundle.meta = next.meta
    bundleStore.replaceGame(next.game)
    setTimeout(() => {
      isApplying.value = false
    }, 0)
  }

  function clear() {
    undoStack.value = []
    redoStack.value = []
    inTransaction = false
    if (pendingTimer) {
      clearTimeout(pendingTimer)
      pendingTimer = null
    }
  }

  return {
    undoStack,
    redoStack,
    isApplying,
    canUndo,
    canRedo,
    pushSnapshot,
    beginEdit,
    undo,
    redo,
    clear,
  }
})
