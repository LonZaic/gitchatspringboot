import { defineStore } from 'pinia'
import { ref } from 'vue'
import { marked } from 'marked'

function buildPartialHtml(text) {
  if (!text) return ''
  try {
    // Synchronous parse for rapid partial updates
    // marked.parse() may return a Promise in some configs, so use parseInline as fallback
    const html = marked.parse(text, { breaks: true })
    // If it returns a Promise (async config), return raw text
    if (html instanceof Promise) return null
    return html
  } catch {
    return null
  }
}

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
      _html: null,
      _showEvidence: false,
    }
    addMessage(assistantMsg)
    streaming.value = true
    error.value = null

    let currentThoughts = []
    let currentContent = ''
    let thoughtDone = false
    let evidenceText = ''

    // Progressive rendering timer
    let renderTimer = null
    const msgIdx = messages.value.length - 1

    function scheduleRender() {
      clearTimeout(renderTimer)
      renderTimer = setTimeout(() => {
        if (currentContent && msgIdx < messages.value.length) {
          const html = buildPartialHtml(currentContent)
          if (html !== null) {
            messages.value[msgIdx] = {
              ...messages.value[msgIdx],
              content: currentContent,
              thoughts: [...currentThoughts],
              thoughtDone,
              evidence: evidenceText,
              _html: html,
            }
          } else {
            messages.value[msgIdx] = {
              ...messages.value[msgIdx],
              content: currentContent,
              thoughts: [...currentThoughts],
              thoughtDone,
              evidence: evidenceText,
            }
          }
        }
      }, 200)
    }

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
        const events = buffer.split('\n\n')
        buffer = events.pop() || ''

        for (const event of events) {
          if (!event.trim()) continue

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
                  currentContent += data.content
                  scheduleRender()
                  break
                case 'evidence':
                  evidenceText = data.content
                  break
                case 'error':
                  throw new Error(data.content)
              }
              // Update reactively (non-html fields)
              if (msgIdx < messages.value.length && data.type !== 'response') {
                messages.value[msgIdx] = {
                  ...messages.value[msgIdx],
                  content: currentContent,
                  thoughts: [...currentThoughts],
                  thoughtDone,
                  evidence: evidenceText,
                }
              }
            } catch (e) {
              if (e.message && (e.message.startsWith('请求失败') || e.message.includes('API') || e.message.startsWith('权限不足'))) {
                throw e
              }
            }
          }
        }
      }

      // Final render after stream completes
      clearTimeout(renderTimer)
      if (currentContent && msgIdx < messages.value.length) {
        let finalHtml = null
        try {
          const result = marked.parse(currentContent, { breaks: true })
          finalHtml = result instanceof Promise ? null : result
        } catch { /* keep null */ }
        messages.value[msgIdx] = {
          ...messages.value[msgIdx],
          content: currentContent,
          thoughts: [...currentThoughts],
          thoughtDone: true,
          evidence: evidenceText,
          _html: finalHtml,
        }
      }
    } catch (e) {
      error.value = e.message
      if (msgIdx < messages.value.length) {
        messages.value[msgIdx] = {
          ...messages.value[msgIdx],
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
