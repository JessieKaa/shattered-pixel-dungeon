import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUiStore = defineStore('ui', () => {
  const loading = ref(false)
  const activeTab = ref<'numeric' | 'form' | 'raw'>('numeric')

  function setLoading(v: boolean) {
    loading.value = v
  }

  function setTab(tab: 'numeric' | 'form' | 'raw') {
    activeTab.value = tab
  }

  return { loading, activeTab, setLoading, setTab }
})
