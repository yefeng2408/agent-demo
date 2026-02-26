<script setup lang="ts">
import { ref } from 'vue'
import { sendMessage,resetUserData } from './api/chatApi'
import { useAgentStore } from './store/agentStore'
//import ClaimGraphPanel from './components/ClaimGraphPanel.vue'
import HelloWorld from './components/HelloWorld.vue'

const store = useAgentStore()

const input = ref('')
const answer = ref('')
const userId = 'debug-user'



async function resetMemory() {
  await resetUserData(userId)
  answer.value = '操作成功！'
}

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
      <h2>Agent Memory Console</h2>

      <input v-model="input" placeholder="输入一句话..." />
      <button @click="chat">Send</button><br><br>
      <button @click="resetMemory">clear user data</button>
      <pre>{{ answer }}</pre>
    </div>

  </div>
</template>