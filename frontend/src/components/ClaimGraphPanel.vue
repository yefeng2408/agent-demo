<!-- <script setup lang="ts">

import { onMounted, onBeforeUnmount, ref, computed } from 'vue'
import { Network } from 'vis-network'
import { useAgentStore } from '../store/agentStore'
import type { ExtractedRelation } from '../types/ExtractedRelation'


const lastDecisionType = ref<string | null>(null)

const flashColor = ref<string | null>(null)
const pulseScale = ref<number>(1)
const glowWidth = ref<number>(2)
const trailWidth = ref(2)
const trailOpacity = ref(1)

const container = ref()
const store = useAgentStore()
// 🔥 DominantDecision 响应式代理（避免 undefined）
const decision = computed(() => store.decision ?? null)

let network: any = null



function shakeAndFade(nodeId:string){

  if(!network) return

  // ⭐ 抖动动画
  network.focus(nodeId,{
    scale:1.35,
    animation:{
      duration:120,
      easingFunction:'easeInOutQuad'
    }
  })

  // ⭐ 再缩回
  setTimeout(()=>{
    network.focus(nodeId,{
      scale:0.9,
      animation:{
        duration:280,
        easingFunction:'easeInOutQuad'
      }
    })
  },150)

  // ⭐ 最终恢复正常
  setTimeout(()=>{
    network.focus(nodeId,{
      scale:1,
      animation:{
        duration:300,
        easingFunction:'easeInOutQuad'
      }
    })
  },450)
}




function focusDominant(nodeId:string){

  if(!network) return

  // network.moveTo({
  //   position: { x:0, y:0 },
  //   scale: 1.2,
  //   animation:{
  //     duration:600,
  //     easingFunction:'easeInOutQuad'
  //   }
  // })

  // 再轻微 focus，让它成为视觉中心
  network.focus(nodeId,{
    scale:1.25,
    animation:{
      duration:600,
      easingFunction:'easeInOutQuad'
    }
  })
}




type DecisionType = 'CONFIRMED' | 'OVERRIDDEN' | 'UNCERTAIN'
type DecisionLike = { type?: DecisionType | string }

// ===== Graph Motion Engine v2 (low-intrusion) =====
let rafId: number | null = null
let animStart = 0
const animDurationMs = 520 // ~0.5s 光轨拖尾
let activeColor: string | null = null

function startRaf() {
  if (rafId != null) return
  rafId = requestAnimationFrame(tick)
}

function stopRaf() {
  if (rafId == null) return
  cancelAnimationFrame(rafId)
  rafId = null
}

function lerp(a: number, b: number, t: number) {
  return a + (b - a) * t
}

// easeOutQuad: 前快后慢，像“脉冲退场”
function easeOut(t: number) {
  return 1 - (1 - t) * (1 - t)
}

function beginTrail(color: string) {
  activeColor = color
  animStart = performance.now()
  startRaf()
}

function endTrail() {
  activeColor = null
  flashColor.value = null
  trailWidth.value = 2
  trailOpacity.value = 1
  pulseScale.value = 1
}

function tick(now: number) {
  // 可能组件已卸载
  if (!container.value) {
    stopRaf()
    return
  }

  if (activeColor) {
    const tRaw = Math.min(1, (now - animStart) / animDurationMs)
    const t = easeOut(tRaw)

    // 光轨：宽度从 7 -> 2；透明度从 1 -> 0
    flashColor.value = activeColor
    trailWidth.value = lerp(7, 2, t)
    trailOpacity.value = lerp(1, 0, t)

    // Dominant pulse：1.35 -> 1.0
    pulseScale.value = lerp(1.35, 1.0, t)

    // 每帧只更新数据，不重建 network
    renderGraph()

    if (tRaw >= 1) {
      endTrail()
      renderGraph()
      stopRaf()
      return
    }

    rafId = requestAnimationFrame(tick)
    return
  }

  // 没有动画就停掉 RAF
  stopRaf()
}

function applyTransition(d: DecisionLike | null | undefined) {
  const type = (d?.type ?? null) as DecisionType | null
  if (!type) return

  // ⭐ 状态没变，不触发动画
  if (lastDecisionType.value === type) return
  lastDecisionType.value = type

  // ⭐ Slot‑Centric v2：动画只代表“Dominant 演化”
  if (type === 'CONFIRMED') beginTrail('#3b82f6')
  else if (type === 'OVERRIDDEN') beginTrail('#f97316')
  else if (type === 'UNCERTAIN') beginTrail('#9ca3af')

  // ⭐ 现在 Dominant 目标是 claim.subjectId（而不是 relation）
  const sid = store.decision?.claim?.subjectId
  if (sid) {
    focusDominant(sid)
    shakeAndFade(sid)
  }
}


function renderGraph() {
  if (!container.value) return

  const claim = store.decision?.claim
  if (!claim) return

  const subjectId = claim.subjectId
  const objectId = claim.objectId
  const predicate = claim.predicate

  const slotId = claim.batch ?? 'slot-init'

  const nodes = [
    {
      id: slotId,
      label: slotId,
      shape: 'box',
      color: '#14b8a6',
      size: 26,
      level: 0
    },
    {
      id: subjectId,
      label: subjectId,
      shape: 'dot',
      color: '#f97316',
      level: 1,
      size: decision.value?.type === 'CONFIRMED'
        ? 30 * pulseScale.value
        : 22
    },
    {
      id: objectId,
      label: objectId,
      shape: 'ellipse',
      color: '#3b82f6',
      level: 2,
      size: 20
    }
  ]

  const edges = [
    {
      from: slotId,
      to: subjectId,
      label: 'DOMINANT',
      arrows: 'to',
      dashes: true,
      color: '#9ca3af',
      width: 2
    },
    {
      from: subjectId,
      to: objectId,
      label: predicate,
      arrows: 'to',
      width: flashColor.value ? trailWidth.value : glowWidth.value,
      color: flashColor.value
        ? { color: flashColor.value, opacity: trailOpacity.value }
        : (claim.polarity ? '#22c55e' : '#ef4444')
    }
  ]

  if (!network) {
    network = new Network(container.value, { nodes, edges }, {
      physics: false,
      layout: {
        hierarchical: {
          enabled: true,
          direction: 'LR',
          levelSeparation: 160,
          nodeSpacing: 120
        }
      },
      edges: {
        smooth: true,
        arrows: { to: { enabled: true } }
      }
    })
  } else {
    network.setData({ nodes, edges })
  }
}


let unsubscribe: (() => void) | null = null

onMounted(() => {
  // 先渲染一帧（如果已有 relation）
  renderGraph()

  // 初始 decision（如果后端有返回 type）
  applyTransition(decision.value as any)

  // 不用 watch：Pinia 原生订阅即可
  unsubscribe = store.$subscribe(() => {
    // relation / decision 任意变化都重绘（low cost：setData）
    renderGraph()
    applyTransition(decision.value as any)
  })
})

onBeforeUnmount(() => {
  try {
    unsubscribe?.()
  } catch (e) {
    // ignore
  }
  unsubscribe = null

  stopRaf()

  if (network) {
    network.destroy()
    network = null
  }
})


</script>

<template>
  <div ref="container" style="height:400px;border:1px solid #ccc;"></div>
</template> -->