import { ref, onMounted, onUnmounted } from 'vue'

const BREAKPOINT = '(max-width: 768px)'

export function useMobileBreakpoint() {
  const isMobile = ref(false)
  let mql: MediaQueryList | null = null

  function sync() {
    if (mql) isMobile.value = mql.matches
  }

  onMounted(() => {
    mql = window.matchMedia(BREAKPOINT)
    sync()
    if (typeof mql.addEventListener === 'function') {
      mql.addEventListener('change', sync)
    } else if (typeof (mql as any).addListener === 'function') {
      ;(mql as any).addListener(sync)
    }
  })

  onUnmounted(() => {
    if (!mql) return
    if (typeof mql.removeEventListener === 'function') {
      mql.removeEventListener('change', sync)
    } else if (typeof (mql as any).removeListener === 'function') {
      ;(mql as any).removeListener(sync)
    }
    mql = null
  })

  return { isMobile }
}
