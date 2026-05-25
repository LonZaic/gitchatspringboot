import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useHistoryStore = defineStore('history', () => {
  const history = ref([])
  const loading = ref(false)

  async function fetchHistory(repoSlug) {
    if (!repoSlug) return
    loading.value = true
    try {
      const res = await fetch(`/api/chat/history?repo_slug=${encodeURIComponent(repoSlug)}`)
      const data = await res.json()
      history.value = data.history || []
    } catch (e) {
      console.error('Failed to load history:', e)
    } finally {
      loading.value = false
    }
  }

  return { history, loading, fetchHistory }
})
