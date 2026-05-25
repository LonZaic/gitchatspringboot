import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const streaming = ref(false)
  const error = ref(null)

  function addMessage(msg) {
    messages.value.push(msg)
  }

  async function sendMessage(question) {
    addMessage({ role: 'user', content: question, id: Date.now() })

    const assistantMsg = {
      role: 'assistant',
      content: '',
      thoughts: [],
      thoughtDone: false,
      evidence: '',
      id: Date.now() + 1,
    }
    addMessage(assistantMsg)
    streaming.value = true
    error.value = null

    let currentThoughts = []
    let currentContent = ''
    let thoughtDone = false
    let evidenceText = ''

    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: question }),
      })

      if (!res.ok) {
        throw new Error(`请求失败: ${res.status}`)
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        // Split by double newline (SSE event separator)
        const events = buffer.split('\n\n')
        buffer = events.pop() || ''

        for (const event of events) {
          if (!event.trim()) continue

          // Parse SSE: handle both "data:..." and "data: ..."
          for (const line of event.split('\n')) {
            const trimmed = line.trim()
            if (!trimmed.startsWith('data:')) continue
            const jsonStr = trimmed.startsWith('data: ')
              ? trimmed.slice(6)
              : trimmed.slice(5)

            try {
              const data = JSON.parse(jsonStr)
              switch (data.type) {
                case 'thought':
                  currentThoughts.push(data.content)
                  break
                case 'thought_done':
                  thoughtDone = true
                  break
                case 'response':
                  if (data.content.startsWith('\n\nEVIDENCE:')) {
                    evidenceText += data.content
                  } else {
                    currentContent += data.content
                  }
                  break
                case 'error':
                  throw new Error(data.content)
              }
              // Update the assistant message reactively
              const idx = messages.value.length - 1
              if (idx >= 0) {
                messages.value[idx] = {
                  ...messages.value[idx],
                  content: currentContent,
                  thoughts: [...currentThoughts],
                  thoughtDone,
                  evidence: evidenceText,
                }
              }
            } catch (e) {
              if (e.message.startsWith('请求失败') || e.message.startsWith('权限不足') || e.message.includes('API')) {
                throw e
              }
            }
          }
        }
      }
    } catch (e) {
      error.value = e.message
      const idx = messages.value.length - 1
      if (idx >= 0) {
        messages.value[idx] = {
          ...messages.value[idx],
          content: currentContent || '',
          thoughts: currentThoughts,
          thoughtDone: true,
          error: e.message,
        }
      }
    } finally {
      streaming.value = false
    }
  }

  function clearMessages() {
    messages.value = []
  }

  return { messages, streaming, error, sendMessage, clearMessages }
})
