<template>
  <div class="analyze">
    <!-- Hero Section -->
    <div class="hero" :class="{ 'hero-compact': repoStore.repoAnalyzed }">
      <h2 class="hero-title" v-if="!repoStore.repoAnalyzed">分析任意 GitHub 仓库</h2>
      <p class="hero-subtitle" v-if="!repoStore.repoAnalyzed">粘贴仓库地址，AI 自动读取代码，随时提问</p>

      <div class="input-card">
        <div class="input-wrap" :class="{ 'has-value': inputUrl, 'error': repoStore.error }">
          <svg class="input-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
          <input
            v-model="inputUrl"
            type="text"
            placeholder="https://github.com/owner/repo"
            class="url-input"
            @keyup.enter="handleAnalyze"
            :disabled="repoStore.loading"
          />
          <button
            class="clear-btn"
            v-if="inputUrl && !repoStore.loading"
            @click="inputUrl = ''; repoStore.clearError()"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
          </button>
        </div>
        <button
          class="analyze-btn"
          :class="{ loading: repoStore.loading }"
          :disabled="!inputUrl.trim() || repoStore.loading"
          @click="handleAnalyze"
        >
          <span v-if="!repoStore.loading" class="btn-content">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
            {{ repoStore.repoAnalyzed ? '重新分析' : '分析仓库' }}
          </span>
          <span v-else class="btn-loading">
            <span class="spinner"></span>
            {{ progressStage.text }}
          </span>
        </button>
      </div>

      <!-- Progress Bar -->
      <transition name="fade">
        <div class="progress-section" v-if="repoStore.loading">
          <div class="progress-bar-track">
            <div class="progress-bar-fill" :style="{ width: progressStage.percent + '%' }"></div>
          </div>
          <div class="progress-stages">
            <div
              v-for="(stage, i) in stages"
              :key="i"
              class="stage-label"
              :class="{
                active: stage.key === progressStage.key,
                done: stage.done,
              }"
            >
              <span class="stage-dot">{{ stage.done ? '✓' : i + 1 }}</span>
              {{ stage.label }}
            </div>
          </div>
        </div>
      </transition>

      <transition name="fade">
        <div class="error-bar" v-if="repoStore.error">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          {{ repoStore.error }}
        </div>
      </transition>
    </div>

    <!-- Results -->
    <transition name="slide-up">
      <div class="results" v-if="repoStore.analysis && repoStore.analysis.stats">
        <div class="stats-row">
          <div class="stat-card">
            <span class="stat-num">{{ repoStore.analysis.stats.files }}</span>
            <span class="stat-label">代码文件</span>
          </div>
          <div class="stat-card">
            <span class="stat-num">{{ repoStore.analysis.stats.chunks }}</span>
            <span class="stat-label">代码片段</span>
          </div>
          <div class="stat-card">
            <span class="stat-num">{{ repoStore.analysis.stats.unique_sources }}</span>
            <span class="stat-label">来源文件</span>
          </div>
        </div>

        <div class="repo-info">
          <div class="repo-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
          </div>
          <div class="repo-path">{{ repoStore.repoKey }}</div>
          <div class="repo-status" v-if="repoStore.analysis.cache_hit">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2a10 10 0 1 0 10 10h-10Z"/></svg>
            缓存
          </div>
        </div>

        <router-link to="/chat" class="start-chat-btn">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          开始提问
        </router-link>
      </div>
    </transition>

    <!-- Empty State -->
    <div class="empty-state" v-if="!repoStore.repoAnalyzed && !repoStore.loading">
      <div class="empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#c7c7cc" stroke-width="1"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
      </div>
      <p class="empty-text">输入 GitHub 仓库地址，<br />开始与代码对话</p>
      <div class="examples">
        <button v-for="ex in examples" :key="ex" class="example-chip" @click="inputUrl = ex">
          {{ ex }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onUnmounted } from 'vue'
import { useRepoStore } from '../stores/repoStore.js'
import { useRouter } from 'vue-router'

const repoStore = useRepoStore()
const router = useRouter()
const inputUrl = ref(repoStore.repoUrl || '')
let progressTimer = null

const stages = [
  { key: 'clone', label: '克隆仓库', percent: 15 },
  { key: 'scan', label: '扫描文件', percent: 35 },
  { key: 'process', label: '处理文档', percent: 60 },
  { key: 'index', label: '构建索引', percent: 85 },
]

const progressStage = reactive({
  key: 'clone',
  text: '克隆仓库...',
  percent: 5,
})

function startProgress() {
  let step = 0
  const steps = [
    { key: 'clone', text: '克隆仓库...', target: 18 },
    { key: 'scan', text: '扫描文件...', target: 38 },
    { key: 'process', text: '处理文档...', target: 65 },
    { key: 'index', text: '构建索引...', target: 88 },
  ]

  progressStage.key = steps[0].key
  progressStage.text = steps[0].text
  progressStage.percent = 3

  let stageIdx = 0
  progressTimer = setInterval(() => {
    const current = steps[stageIdx]
    if (!current) return

    // Advance progress toward target
    const remaining = current.target - progressStage.percent
    if (remaining > 0.5) {
      progressStage.percent += Math.max(0.3, remaining * 0.06)
      if (progressStage.key !== current.key) {
        progressStage.key = current.key
        progressStage.text = current.text
      }
    } else if (stageIdx < steps.length - 1) {
      stageIdx++
      progressStage.key = steps[stageIdx].key
      progressStage.text = steps[stageIdx].text
    } else {
      // Stay at 90% until real completion
      progressStage.percent = Math.min(progressStage.percent + 0.1, 90)
    }
  }, 150)
}

function stopProgress() {
  if (progressTimer) {
    clearInterval(progressTimer)
    progressTimer = null
  }
  progressStage.percent = 100
  progressStage.text = '分析完成'
}

onUnmounted(() => stopProgress())

const examples = [
  'https://github.com/vuejs/core',
  'https://github.com/spring-projects/spring-boot',
  'https://github.com/langchain-ai/langchain',
]

async function handleAnalyze() {
  const url = inputUrl.value.trim()
  if (!url || repoStore.loading) return
  startProgress()
  const ok = await repoStore.analyze(url)
  stopProgress()
  if (ok) {
    router.push('/chat')
  }
}
</script>

<style scoped>
.analyze {
  display: flex;
  flex-direction: column;
  align-items: center;
}

/* Hero */
.hero {
  width: 100%;
  max-width: 600px;
  text-align: center;
  padding-top: 40px;
  transition: padding 0.4s ease;
}

.hero-compact {
  padding-top: 8px;
}

.hero-title {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.02em;
  margin-bottom: 8px;
  color: #1d1d1f;
}

.hero-subtitle {
  font-size: 16px;
  color: #86868b;
  margin-bottom: 32px;
}

/* Input Card */
.input-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04), 0 1px 2px rgba(0, 0, 0, 0.02);
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: box-shadow 0.2s ease;
}

.input-card:focus-within {
  box-shadow: 0 4px 20px rgba(0, 113, 227, 0.08), 0 2px 4px rgba(0, 0, 0, 0.02);
}

.input-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #f5f5f7;
  border-radius: 12px;
  padding: 0 14px;
  border: 2px solid transparent;
  transition: all 0.2s ease;
}

.input-wrap:focus-within {
  border-color: #0071e3;
  background: #ffffff;
}

.input-wrap.has-value {
  background: #ffffff;
}

.input-wrap.error {
  border-color: #ff3b30;
  background: #fff5f5;
}

.input-icon {
  flex-shrink: 0;
  color: #86868b;
}

.url-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 15px;
  padding: 12px 0;
  color: #1d1d1f;
}

.url-input::placeholder {
  color: #aeaeb2;
}

.clear-btn {
  flex-shrink: 0;
  background: none;
  border: none;
  color: #86868b;
  padding: 4px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.clear-btn:hover {
  background: #e8e8ed;
  color: #1d1d1f;
}

.analyze-btn {
  width: 100%;
  padding: 12px 24px;
  background: #0071e3;
  color: #ffffff;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all 0.2s ease;
}

.analyze-btn:hover:not(:disabled) {
  background: #0077ed;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 113, 227, 0.3);
}

.analyze-btn:active:not(:disabled) {
  transform: translateY(0);
  box-shadow: none;
}

.analyze-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-content {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-loading {
  display: flex;
  align-items: center;
  gap: 10px;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2.5px solid rgba(255, 255, 255, 0.3);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Progress Bar */
.progress-section {
  margin-top: 20px;
  width: 100%;
  max-width: 600px;
}

.progress-bar-track {
  width: 100%;
  height: 4px;
  background: #e8e8ed;
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 16px;
}

.progress-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #0071e3, #40b4ff);
  border-radius: 2px;
  transition: width 0.3s ease;
}

.progress-stages {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.stage-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #aeaeb2;
  transition: all 0.3s ease;
}

.stage-label.active {
  color: #0071e3;
  font-weight: 500;
}

.stage-label.done {
  color: #34c759;
}

.stage-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #e8e8ed;
  font-size: 11px;
  font-weight: 600;
  color: #86868b;
  flex-shrink: 0;
  transition: all 0.3s ease;
}

.stage-label.active .stage-dot {
  background: #0071e3;
  color: #ffffff;
}

.stage-label.done .stage-dot {
  background: #34c759;
  color: #ffffff;
}

/* Error */
.error-bar {
  margin-top: 16px;
  padding: 12px 16px;
  background: #fff5f5;
  border: 1px solid #ffd7d5;
  border-radius: 12px;
  color: #c41e3a;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  text-align: left;
}

/* Results */
.results {
  width: 100%;
  max-width: 600px;
  margin-top: 28px;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  background: #ffffff;
  border-radius: 14px;
  padding: 20px 16px;
  text-align: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  transition: transform 0.2s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
}

.stat-num {
  display: block;
  font-size: 28px;
  font-weight: 700;
  color: #1d1d1f;
  letter-spacing: -0.02em;
}

.stat-label {
  display: block;
  font-size: 13px;
  color: #86868b;
  margin-top: 4px;
}

.repo-info {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #ffffff;
  border-radius: 12px;
  padding: 14px 18px;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.repo-icon {
  color: #86868b;
  flex-shrink: 0;
}

.repo-path {
  flex: 1;
  font-size: 14px;
  font-weight: 500;
  color: #1d1d1f;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-status {
  font-size: 12px;
  color: #86868b;
  display: flex;
  align-items: center;
  gap: 4px;
  background: #f0f0f2;
  padding: 4px 10px;
  border-radius: 20px;
}

.start-chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 14px;
  background: #0071e3;
  color: #ffffff;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  transition: all 0.2s ease;
}

.start-chat-btn:hover {
  background: #0077ed;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 113, 227, 0.3);
}

/* Empty State */
.empty-state {
  text-align: center;
  margin-top: 64px;
}

.empty-icon {
  margin-bottom: 16px;
}

.empty-text {
  font-size: 15px;
  color: #86868b;
  line-height: 1.6;
}

.examples {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 20px;
}

.example-chip {
  padding: 8px 16px;
  background: #ffffff;
  border: 1px solid #e8e8ed;
  border-radius: 20px;
  font-size: 13px;
  color: #86868b;
  transition: all 0.2s ease;
}

.example-chip:hover {
  border-color: #0071e3;
  color: #0071e3;
  background: #f0f7ff;
}

/* Transitions */
.fade-enter-active, .fade-leave-active {
  transition: all 0.3s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.slide-up-enter-active {
  transition: all 0.4s cubic-bezier(0.16, 1, 0.3, 1);
}
.slide-up-enter-from {
  opacity: 0;
  transform: translateY(20px);
}
</style>
