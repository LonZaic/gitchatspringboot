<template>
  <div class="app">
    <header class="header">
      <div class="header-inner">
        <div class="brand" @click="$router.push('/analyze')">
          <span class="brand-icon">&#x1F4AC;</span>
          <h1 class="brand-name">GitChat</h1>
          <span class="brand-tag">AI 代码问答</span>
        </div>
        <nav class="nav" v-if="repoStore.repoAnalyzed">
          <router-link
            to="/analyze"
            class="nav-link"
            :class="{ active: $route.path === '/analyze' }"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
            分析
          </router-link>
          <router-link
            to="/chat"
            class="nav-link"
            :class="{ active: $route.path === '/chat' }"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
            问答
          </router-link>
        </nav>
      </div>
    </header>

    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { useRepoStore } from './stores/repoStore.js'
const repoStore = useRepoStore()
</script>

<style>
*,
*::before,
*::after {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html {
  font-size: 16px;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

body {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Helvetica Neue', sans-serif;
  background: #f5f5f7;
  color: #1d1d1f;
  line-height: 1.6;
  min-height: 100vh;
}

a { color: inherit; text-decoration: none; }
button { cursor: pointer; font-family: inherit; }
input { font-family: inherit; }
::-webkit-scrollbar { width: 6px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: #c7c7cc; border-radius: 3px; }
::-webkit-scrollbar-thumb:hover { background: #a8a8ad; }
</style>

<style scoped>
.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.header {
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-inner {
  max-width: 880px;
  margin: 0 auto;
  padding: 0 24px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}

.brand-icon {
  font-size: 22px;
  line-height: 1;
}

.brand-name {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.brand-tag {
  font-size: 12px;
  color: #86868b;
  font-weight: 400;
  padding: 2px 8px;
  background: #f0f0f2;
  border-radius: 20px;
}

.nav {
  display: flex;
  gap: 4px;
  background: #f0f0f2;
  border-radius: 10px;
  padding: 3px;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 14px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  color: #86868b;
  transition: all 0.2s ease;
}

.nav-link:hover {
  color: #1d1d1f;
  background: rgba(255, 255, 255, 0.5);
}

.nav-link.active {
  color: #1d1d1f;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.main {
  flex: 1;
  max-width: 880px;
  width: 100%;
  margin: 0 auto;
  padding: 32px 24px 80px;
}
</style>
