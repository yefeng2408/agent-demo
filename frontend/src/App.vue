<script setup lang="ts">
import { ref } from 'vue'
import { sendMessage } from './api/chatApi'
import { useAgentStore } from './store/agentStore'
//import ClaimGraphPanel from './components/ClaimGraphPanel.vue'
import HelloWorld from './components/HelloWorld.vue'

const store = useAgentStore()

const input = ref('')
const answer = ref('')

async function chat() {
  const res = await sendMessage(input.value)
  // ⭐⭐⭐ 关键修改
  store.updateFromAnswerResult(res.graph)
  answer.value = res.explain
}

</script>

<template>
  <div>

    <!-- GraphPanel 放最上面 -->
    <HelloWorld />
    <div style="padding:40px">
      <h2>Memory Agent Console</h2>

      <input v-model="input" placeholder="输入一句话..." />
      <button @click="chat">Send</button>

      <pre>{{ answer }}</pre>
    </div>

  </div>
</template>