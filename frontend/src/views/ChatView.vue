<template>
  <div class="chat">
    <!-- Welcome -->
    <div class="welcome" v-if="chatStore.messages.length === 0">
      <div class="welcome-icon">
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#c7c7cc" stroke-width="1.2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
      </div>
      <h3 class="welcome-title">{{ repoStore.repoKey || '仓库已就绪' }}</h3>
      <p class="welcome-text">提问关于代码架构、功能逻辑、实现细节等任何问题</p>
      <div class="suggestions" v-if="!chatStore.streaming">
        <button
          v-for="s in suggestions"
          :key="s"
          class="suggestion-chip"
          @click="ask(s)"
        >
          {{ s }}
        </button>
      </div>
    </div>

    <!-- Messages -->
    <div class="messages" ref="messagesRef">
      <div
        v-for="msg in chatStore.messages"
        :key="msg.id"
        class="msg-row"
        :class="msg.role"
      >
        <div class="msg-avatar" v-if="msg.role === 'assistant'">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2a4 4 0 0 1 4 4v2a4 4 0 0 1-8 0V6a4 4 0 0 1 4-4Z"/><path d="M5 13h14"/><path d="M5 17h10"/><path d="M5 21h6"/></svg>
        </div>
        <div class="msg-body">
          <div class="msg-bubble" v-if="msg.role === 'user'">
            {{ msg.content }}
          </div>
          <div class="msg-bubble assistant-bubble" v-if="msg.role === 'assistant'">
            <!-- Thoughts -->
            <div class="thoughts" v-if="msg.thoughts.length > 0 && !msg.thoughtDone">
              <div class="thought-item" v-for="(t, i) in msg.thoughts" :key="i">
                <span class="thought-dot"></span>
                {{ t }}
              </div>
            </div>

            <!-- Rendered markdown (progressive during stream, final after done) -->
            <div v-if="msg._html" class="msg-content markdown-body" v-html="msg._html"></div>

            <!-- Fallback: raw text when html not yet rendered -->
            <div v-else-if="msg.content" class="msg-content-stream">{{ msg.content }}</div>

            <!-- Cursor when waiting for first token -->
            <span class="cursor" v-if="chatStore.streaming && !msg.content && msg.thoughts.length === 0">▊</span>

            <!-- Error -->
            <div class="msg-error" v-if="msg.error">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              {{ msg.error }}
            </div>

            <!-- Evidence (from separate SSE event) -->
            <div class="evidence" v-if="msg.evidence && msg.thoughtDone && msg.content">
              <div class="evidence-header" @click="msg._showEvidence = !msg._showEvidence">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
                参考文件
                <svg class="chevron" :class="{ open: msg._showEvidence }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
              </div>
              <div class="evidence-body" v-if="msg._showEvidence">
                <a v-for="(link, i) in parseEvidenceLinks(msg.evidence)" :key="i" :href="link.url" target="_blank" class="evidence-link">{{ link.name }}</a>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- No repo -->
    <div class="no-repo" v-if="!repoStore.repoAnalyzed && chatStore.messages.length === 0">
      <p>请先在「分析」页面导入一个 GitHub 仓库</p>
      <router-link to="/analyze" class="go-analyze-btn">去分析</router-link>
    </div>

    <!-- Input Bar -->
    <div class="input-bar" v-if="repoStore.repoAnalyzed">
      <div class="input-inner">
        <input
          v-model="question"
          type="text"
          placeholder="输入你的问题..."
          class="chat-input"
          @keyup.enter="send"
          :disabled="chatStore.streaming"
        />
        <button
          class="send-btn"
          :class="{ active: question.trim() }"
          :disabled="!question.trim() || chatStore.streaming"
          @click="send"
        >
          <svg v-if="!chatStore.streaming" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
          <span v-else class="spinner-sm"></span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { useChatStore } from '../stores/chatStore.js'
import { useRepoStore } from '../stores/repoStore.js'
import { marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

marked.use(markedHighlight({
  langPrefix: 'hljs language-',
  highlight(code, lang) {
    const language = hljs.getLanguage(lang) ? lang : 'plaintext'
    return hljs.highlight(code, { language }).value
  },
}))

const chatStore = useChatStore()
const repoStore = useRepoStore()
const question = ref('')
const messagesRef = ref(null)

const suggestions = [
  '这个项目是做什么的？',
  '项目结构是怎么样的？',
  '核心功能是如何实现的？',
]

function parseEvidenceLinks(text) {
  if (!text) return []
  const links = []
  const regex = /-\s*\[(.+?)\]\((.+?)\)/g
  let match
  while ((match = regex.exec(text)) !== null) {
    links.push({ name: match[1].split('/').pop(), url: match[2] })
  }
  return links
}

// Scroll to bottom on new messages or content updates
watch(
  () => chatStore.messages.map(m => m.content + (m._html ? '1' : '0')).join('|'),
  async () => {
    await nextTick()
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  },
  { flush: 'post' }
)

async function send() {
  const q = question.value.trim()
  if (!q || chatStore.streaming) return
  question.value = ''
  await chatStore.sendMessage(q)
}

function ask(text) {
  question.value = text
  send()
}
</script>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px - 64px);
  position: relative;
}

.welcome {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 40px 20px;
}

.welcome-icon { margin-bottom: 16px; }

.welcome-title {
  font-size: 22px;
  font-weight: 600;
  margin-bottom: 8px;
  color: #1d1d1f;
}

.welcome-text {
  font-size: 15px;
  color: #86868b;
  margin-bottom: 24px;
}

.suggestions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 360px;
}

.suggestion-chip {
  padding: 12px 20px;
  background: #ffffff;
  border: 1px solid #e8e8ed;
  border-radius: 12px;
  font-size: 14px;
  color: #1d1d1f;
  text-align: left;
  transition: all 0.2s ease;
}

.suggestion-chip:hover {
  border-color: #0071e3;
  background: #f0f7ff;
  transform: translateX(4px);
}

/* Messages */
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.msg-row {
  display: flex;
  gap: 12px;
  max-width: 100%;
}

.msg-row.user { justify-content: flex-end; }

.msg-avatar {
  width: 32px;
  height: 32px;
  background: #0071e3;
  color: #fff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 4px;
}

.msg-body {
  flex: 1;
  max-width: 85%;
  min-width: 0;
}

.msg-bubble {
  padding: 14px 18px;
  border-radius: 16px;
  font-size: 15px;
  line-height: 1.6;
  word-wrap: break-word;
  overflow-wrap: break-word;
}

.msg-row.user .msg-bubble {
  background: #0071e3;
  color: #ffffff;
  border-bottom-right-radius: 4px;
}

.assistant-bubble {
  background: #ffffff;
  color: #1d1d1f;
  border-bottom-left-radius: 4px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.msg-content-stream {
  white-space: pre-wrap;
  line-height: 1.7;
  word-break: break-word;
}

/* Markdown body - rendered output */
:deep(.markdown-body) {
  line-height: 1.7;
  word-break: break-word;
}
:deep(.markdown-body p) { margin: 8px 0; }
:deep(.markdown-body ul),
:deep(.markdown-body ol) { padding-left: 20px; margin: 8px 0; }
:deep(.markdown-body h1),
:deep(.markdown-body h2),
:deep(.markdown-body h3),
:deep(.markdown-body h4) { margin: 16px 0 8px; font-weight: 600; }
:deep(.markdown-body h1) { font-size: 1.3em; }
:deep(.markdown-body h2) { font-size: 1.15em; }
:deep(.markdown-body h3) { font-size: 1.05em; }
:deep(.markdown-body a) { color: #0071e3; text-decoration: underline; }
:deep(.markdown-body code:not(pre code)) {
  background: #f0f0f2;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
  font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
}
:deep(.markdown-body pre) {
  background: #f6f8fa;
  border-radius: 10px;
  padding: 16px;
  overflow-x: auto;
  margin: 12px 0;
  font-size: 13px;
  line-height: 1.5;
  border: 1px solid #e8e8ed;
}
:deep(.markdown-body pre code) {
  background: none;
  padding: 0;
  font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
}
:deep(.markdown-body table) {
  border-collapse: collapse;
  margin: 12px 0;
  width: 100%;
  font-size: 14px;
}
:deep(.markdown-body th),
:deep(.markdown-body td) {
  border: 1px solid #e8e8ed;
  padding: 8px 12px;
  text-align: left;
}
:deep(.markdown-body th) { background: #f6f8fa; font-weight: 600; }
:deep(.markdown-body blockquote) {
  border-left: 3px solid #0071e3;
  margin: 12px 0;
  padding: 8px 16px;
  color: #86868b;
  background: #f9f9fb;
}
:deep(.markdown-body img) { max-width: 100%; }
:deep(.markdown-body hr) { border: none; border-top: 1px solid #e8e8ed; margin: 16px 0; }

/* Cursor blink */
.cursor {
  animation: blink 0.8s step-end infinite;
  color: #0071e3;
}
@keyframes blink { 50% { opacity: 0; } }

/* Thoughts */
.thoughts {
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f2;
}
.thought-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #86868b;
  padding: 3px 0;
}
.thought-dot {
  width: 5px;
  height: 5px;
  background: #0071e3;
  border-radius: 50%;
  flex-shrink: 0;
  animation: thought-pulse 1.2s ease-in-out infinite;
}
@keyframes thought-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

/* Error */
.msg-error {
  margin-top: 8px;
  padding: 10px 14px;
  background: #fff5f5;
  border-radius: 10px;
  color: #c41e3a;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}

/* Evidence */
.evidence {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f2;
}
.evidence-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #86868b;
  cursor: pointer;
  user-select: none;
  padding: 4px 0;
  transition: color 0.15s ease;
}
.evidence-header:hover { color: #1d1d1f; }
.chevron { transition: transform 0.2s ease; }
.chevron.open { transform: rotate(180deg); }
.evidence-body {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.evidence-link {
  display: inline-block;
  padding: 4px 12px;
  background: #f0f7ff;
  color: #0071e3;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  transition: all 0.15s ease;
}
.evidence-link:hover {
  background: #0071e3;
  color: #ffffff;
}

/* No Repo */
.no-repo {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: #86868b;
  font-size: 15px;
}
.go-analyze-btn {
  padding: 10px 24px;
  background: #0071e3;
  color: #ffffff;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.2s ease;
}
.go-analyze-btn:hover {
  background: #0077ed;
  transform: translateY(-1px);
}

/* Input Bar */
.input-bar {
  padding: 12px 0 0;
  background: linear-gradient(to top, #f5f5f7 60%, transparent);
  position: sticky;
  bottom: 0;
}
.input-inner {
  display: flex;
  gap: 8px;
  background: #ffffff;
  border-radius: 14px;
  padding: 6px 6px 6px 18px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  border: 1px solid rgba(0, 0, 0, 0.04);
}
.chat-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 15px;
  padding: 8px 0;
  color: #1d1d1f;
}
.chat-input::placeholder { color: #aeaeb2; }
.send-btn {
  width: 38px;
  height: 38px;
  background: #e8e8ed;
  color: #86868b;
  border: none;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  flex-shrink: 0;
}
.send-btn.active { background: #0071e3; color: #ffffff; }
.send-btn.active:hover { background: #0077ed; transform: scale(1.05); }
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.spinner-sm {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
