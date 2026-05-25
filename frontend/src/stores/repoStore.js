import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useRepoStore = defineStore('repo', () => {
  const repoUrl = ref('')
  const repoKey = ref('')
  const analysis = ref(null)
  const loading = ref(false)
  const error = ref(null)
  const repoAnalyzed = computed(() => analysis.value !== null)

  async function analyze(url) {
    loading.value = true
    error.value = null
    try {
      const res = await fetch('/api/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ repoUrl: url }),
      })
      const data = await res.json()
      if (data.status === 'error') {
        throw new Error(data.message + (data.detail ? `: ${data.detail}` : ''))
      }
      repoUrl.value = url
      repoKey.value = url
        .replace(/https?:\/\/github\.com\//, '')
        .replace(/\.git$/, '')
        .replace(/\/$/, '')
      analysis.value = data
      return true
    } catch (e) {
      error.value = e.message
      return false
    } finally {
      loading.value = false
    }
  }

  function clearError() {
    error.value = null
  }

  return { repoUrl, repoKey, analysis, loading, error, repoAnalyzed, analyze, clearError }
})
