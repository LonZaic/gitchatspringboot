import { createRouter, createWebHistory } from 'vue-router'
import AnalyzeView from '../views/AnalyzeView.vue'
import ChatView from '../views/ChatView.vue'

const routes = [
  { path: '/', redirect: '/analyze' },
  { path: '/analyze', name: 'Analyze', component: AnalyzeView },
  { path: '/chat', name: 'Chat', component: ChatView },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
