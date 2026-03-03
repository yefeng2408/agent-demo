<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { sendMessage, resetUserData } from './api/chatApi'
import { useAgentStore } from './store/agentStore'
import HelloWorld from './components/HelloWorld.vue'

const store = useAgentStore()

const input = ref('')
const loading = ref(false)
const userId = 'debug-user'

interface Message {
  role: 'user' | 'assistant'
  content: string
  time: string
}

const messages = ref<Message[]>([])

function formatNow(): string {
  const now = new Date()
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ` +
         `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

function scrollBottom() {
  nextTick(() => {
    const container = document.querySelector('.chat-container')
    if (container) container.scrollTop = container.scrollHeight
  })
}

async function typeWriter(targetMsg: Message, fullText: string) {
  // 找到当前消息在数组中的索引
  const index = messages.value.indexOf(targetMsg)

  // 如果未找到，直接退出（避免 TS 报 undefined）
  if (index === -1) return

  // 先清空内容（通过数组访问，保证响应式）
  messages.value[index]!.content = ''

  for (let i = 0; i < fullText.length; i++) {
    // 使用非空断言，避免 TS2532
    messages.value[index]!.content += fullText[i]

    await nextTick()
    scrollBottom()

    await new Promise(resolve => setTimeout(resolve, 90))
  }
}

async function resetMemory() {
  await resetUserData(userId)
  messages.value = []
}

async function chat() {
  if (!input.value.trim()) return

  const userText = input.value

  messages.value.push({
    role: 'user',
    content: userText,
    time: formatNow()
  })

  input.value = ''
  loading.value = true

  const assistantMsg: Message = {
    role: 'assistant',
    content: '',
    time: formatNow()
  }

  messages.value.push(assistantMsg)
  scrollBottom()

  try {
    const res = await sendMessage(userText)
    store.updateFromAnswerResult(res.graph)
    await typeWriter(assistantMsg, res.explain)
  } finally {
    loading.value = false
    scrollBottom()
  }
}

</script>

<template>
  <div class="page">
    <HelloWorld />

    <div class="console-wrapper">
      <h2>Memory  Agent  Console</h2>

      <div class="chat-container">
        <div
          v-for="(msg, index) in messages"
          :key="index"
          :class="['bubble', msg.role]"
        >
          <div class="meta">
            <span>{{ msg.role === 'user' ? 'You' : 'Agent' }}</span>
            <span>{{ msg.time }}</span>
          </div>
          <div class="content">
            {{ msg.content }}
          </div>
        </div>

        <div v-if="loading" class="bubble assistant typing">
          <div class="content">Typing...</div>
        </div>
      </div>

      <div class="input-bar">
        <input
          v-model="input"
          placeholder="输入一句话..."
          @keyup.enter="chat"
        />
        <button @click="chat" :disabled="loading">Send</button>
        <button @click="resetMemory">Clear User Data</button>
      </div>

            <div class="input-bar222">
          <h5>
            目前支持声明“我有特斯拉汽车”、“我没有特斯拉汽车” 形成认知博弈，然后提问“我是否拥有特斯拉汽车”，形成冲突裁决。
          后续考虑增加更多的谓词(Predicate)，以满足更多类型的声明。当前正在优化图谱结构
          </h5>
      </div>

    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #0f172a;
  display: flex;
  justify-content: center;
  padding: 40px 0;
}

.console-wrapper {
  width: 900px;
  background: #1e293b;
  border-radius: 12px;
  padding: 30px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.4);
  color: #e2e8f0;
}

.console-wrapper h2 {
  text-align: center;
  margin-bottom: 30px;
}

.chat-container {
  height: 420px;
  overflow-y: auto;
  padding: 20px;
  background: #111827;
  border-radius: 10px;
  margin-bottom: 20px;
}

.bubble {
  max-width: 75%;
  padding: 14px 18px;
  border-radius: 12px;
  margin-bottom: 16px;
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble.user {
  background: #2563eb;
  margin-left: auto;
  color: white;
}

.bubble.assistant {
  background: #334155;
}

.meta {
  font-size: 12px;
  opacity: 0.6;
  margin-bottom: 6px;
  display: flex;
  justify-content: space-between;
}

.content {
  font-size: 14px;
  line-height: 1.6;
}

.input-bar {
  display: flex;
  gap: 10px;
}

.input-bar input {
  flex: 1;
  padding: 10px;
  border-radius: 8px;
  border: none;
  background: #0f172a;
  color: #e2e8f0;
}

.input-bar button {
  padding: 10px 16px;
  border-radius: 8px;
  border: none;
  cursor: pointer;
  background: #2563eb;
  color: white;
}

.typing {
  opacity: 0.7;
}
</style>
