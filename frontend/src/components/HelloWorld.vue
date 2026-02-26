<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Network } from 'vis-network'
import { DataSet } from 'vis-data'
import { useAgentStore } from '../store/agentStore'
import type { ExtractedRelation } from '../types/ExtractedRelation'

// =====================================================
// 🔥 Slot-Centric v9 — Live Cognitive Evolution (Interview Edition)
// ✅ Snapshot History + Timeline Replay + Graph Highlight Animation
// =====================================================

// ===== view models (align with /chat/personal response) =====
type DecisionType = 'CONFIRMED' | 'OVERRIDDEN' | 'UNCERTAIN' | string

interface DecisionClaim {
  elementId?: string
  subjectId: string
  predicate: string
  objectId: string
  quantifier?: string
  polarity?: boolean
  epistemicStatus?: string
  confidence?: number
  source?: string
  batch?: string
  updatedAt?: string
  lastStatusChangedAt?: string
  priority?: number
  momentum?: number
  lastMomentumAt?: string | null
}

interface DecisionView {
  type?: DecisionType
  claim?: DecisionClaim
  reason?: string
  final?: boolean
}

type Snapshot = {
  at: string              // 展示用时间
  key: string             // 去重 key
  decision: DecisionView | null
  relation: ExtractedRelation | null
  note: string            // timeline 文本
}

// ===== store bindings =====
const store = useAgentStore()

const liveDecision = computed<DecisionView | null>(() => {
  if (store.decision) return store.decision as unknown as DecisionView
  const g: any = (store as any).graph
  return (g?.decision ?? null) as DecisionView | null
})

const liveRelation = computed<ExtractedRelation | null>(() => {
  if (store.relation) return store.relation as ExtractedRelation
  const g: any = (store as any).graph
  return (g?.relation ?? null) as ExtractedRelation | null
})

const challenger = computed<DecisionClaim | null>(() => {
  const g: any = (store as any).graph
  return (g?.challenger ?? null) as DecisionClaim | null
})

// =====================================================
// 🧠 v9: Snapshot Timeline (local history)
// =====================================================
const history = ref<Snapshot[]>([])
const activeIndex = ref<number>(-1) // -1 = live
const isPlaying = ref(false)
const playSpeedMs = ref(1200)
let playTimer: number | null = null

function clone<T>(v: T): T {
  // 简单深拷贝即可（你的数据结构是 JSON 结构）
  return v ? JSON.parse(JSON.stringify(v)) : v
}

function buildSnapshotKey(d: DecisionView | null, r: ExtractedRelation | null) {
  const did = d?.claim?.elementId ?? ''
  const du = d?.claim?.updatedAt ?? d?.claim?.lastStatusChangedAt ?? ''
  const rs = r ? `${r.subjectId}|${r.predicateType}|${r.objectId}|${r.quantifier}|${r.polarity}|${r.confidence}` : ''
  return `${did}::${du}::${rs}`
}

function snapshotNote(d: DecisionView | null, r: ExtractedRelation | null) {
  const type = d?.type ?? 'NONE'
  const status = d?.claim?.epistemicStatus ?? '-'
  const pred = d?.claim?.predicate ?? r?.predicateType ?? '-'
  const pol = typeof d?.claim?.polarity === 'boolean' ? (d!.claim!.polarity ? 'T' : 'F') : (typeof r?.polarity === 'boolean' ? (r!.polarity ? 'T' : 'F') : '?')
  const conf = typeof d?.claim?.confidence === 'number'
    ? (Math.floor(d!.claim!.confidence * 1000) / 1000).toFixed(3)
    : (typeof r?.confidence === 'number' ? (Math.floor(r!.confidence * 1000) / 1000).toFixed(3) : '-')
  return `${type} | ${status} | ${pred} | polarity:${pol} | conf:${conf}`
}

function pushSnapshotFromLive() {
  const d = liveDecision.value
  const r = liveRelation.value
  if (!d && !r) return

  const key = buildSnapshotKey(d, r)
  const last = history.value[history.value.length - 1]
  if (last?.key === key) return // 去重：避免同一次渲染重复塞 history

  const at =
    d?.claim?.updatedAt ??
    d?.claim?.lastStatusChangedAt ??
    new Date().toISOString()

  history.value.push({
    at,
    key,
    decision: clone(d),
    relation: clone(r),
    note: snapshotNote(d, r),
  })

  // 控制 history 长度（面试演示足够）
  if (history.value.length > 30) history.value.shift()

  // 如果正在 live 模式，不强制跳走；如果正在回放，就保持 activeIndex 不变
}

const decision = computed<DecisionView | null>(() => {
  if (activeIndex.value >= 0) return history.value[activeIndex.value]?.decision ?? null
  return liveDecision.value
})

const relation = computed<ExtractedRelation | null>(() => {
  if (activeIndex.value >= 0) return history.value[activeIndex.value]?.relation ?? null
  return liveRelation.value
})

function setActive(i: number) {
  if (i < 0) {
    activeIndex.value = -1
  } else {
    activeIndex.value = Math.min(Math.max(0, i), history.value.length - 1)
  }
  renderGraph(true) // 强制触发一次高亮动画
}

function togglePlay() {
  isPlaying.value = !isPlaying.value
  if (!isPlaying.value) stopPlay()
  else startPlay()
}

function startPlay() {
  stopPlay()
  if (history.value.length === 0) return
  // 如果当前是 live，从头开始；否则从当前位置继续
  if (activeIndex.value < 0) activeIndex.value = 0

  isPlaying.value = true
  playTimer = window.setInterval(() => {
    if (history.value.length === 0) return
    const next = activeIndex.value + 1
    if (next >= history.value.length) {
      // 播放完停住
      stopPlay()
      return
    }
    activeIndex.value = next
    renderGraph(true)
  }, playSpeedMs.value)
}

function stopPlay() {
  isPlaying.value = false
  if (playTimer) {
    window.clearInterval(playTimer)
    playTimer = null
  }
}

function onSpeedChange() {
  if (isPlaying.value) startPlay()
}

// =====================================================
// 🧠 Graph Renderer + v9 Highlight Animation
// =====================================================
const container = ref<HTMLDivElement | null>(null)
let network: Network | null = null

// Use DataSet so we can update edges/nodes without touching private Network internals
let nodesDS: DataSet<any> | null = null
let edgesDS: DataSet<any> | null = null

function resetDataSets(nodes: any[], edges: any[]) {
  if (!nodesDS) nodesDS = new DataSet(nodes)
  else {
    nodesDS.clear()
    nodesDS.add(nodes)
  }

  if (!edgesDS) edgesDS = new DataSet(edges)
  else {
    edgesDS.clear()
    edgesDS.add(edges)
  }
}

function updateEdge(id: string, patch: any) {
  if (!edgesDS) return
  const existed = edgesDS.get(id)
  if (!existed) return
  edgesDS.update({ id, ...patch })
}

let lastRenderKey = '' // 用于判断是否需要 flash

function renderGraph(flash = false) {
  if (!container.value) return
  const r = relation.value
  if (!r) return

  const d = decision.value
  const slotSource = (d?.claim?.source || r.source || 'USER_STATEMENT') as string
  const slotId = `${r.subjectId}|${r.predicateType}|${r.objectId}|${r.quantifier}`

  const isConfirmed = d?.type === 'CONFIRMED'
  const isOverridden = d?.type === 'OVERRIDDEN'
  const confNum =
    typeof d?.claim?.confidence === 'number'
      ? d!.claim!.confidence
      : typeof r.confidence === 'number'
      ? r.confidence
      : 0

  // node glow (v9): 回放模式更明显
  const subjectGlow = activeIndex.value >= 0 ? 10 : 0

  // 🧠 v10 Cognitive War Mode — challenger presence
  const challengerClaim = challenger.value
  const hasChallenger =
    !!challengerClaim &&
    challengerClaim.objectId === r.objectId &&
    challengerClaim.predicate === r.predicateType

  const nodes: any[] = [
    {
      id: slotId,
      label: `slot\n(${slotSource})`,
      shape: 'box',
      color: '#14b8a6',
      level: 0,
      font: { color: '#0f172a' },
      borderWidth: 2,
    },
    {
      id: r.subjectId,
      label: r.subjectId,
      shape: 'dot',
      color: '#f97316',
      level: 1,
      size: isConfirmed ? 34 : 28,
      borderWidth: isConfirmed ? 6 : 3,
      font: { color: '#111827' },
      shadow: subjectGlow
        ? {
            enabled: true,
            color: isConfirmed ? 'rgba(59,130,246,0.55)' : 'rgba(249,115,22,0.55)',
            size: subjectGlow,
            x: 0,
            y: 0,
          }
        : undefined,
    },
    {
      id: r.objectId,
      label: r.objectId,
      shape: 'ellipse',
      color: '#3b82f6',
      level: 2,
      size: 22,
      font: { color: '#111827' },
    },
    ...(hasChallenger
      ? [
          {
            id: 'challenger_node',
            label: 'challenger',
            shape: 'dot',
            color: '#f97316',
            level: 1,
            size: 26,
            borderWidth: 3,
            font: { color: '#111827' },
            shadow: {
              enabled: true,
              color: 'rgba(249,115,22,0.55)',
              size: 8,
              x: 0,
              y: 0,
            },
          },
        ]
      : []),
  ]

  // v9: edge ids for animation
  const edges: any[] = [
    {
      id: 'e_dominant',
      from: slotId,
      to: r.subjectId,
      label: 'DOMINANT',
      dashes: true,
      arrows: 'to',
      color: { color: '#9ca3af' },
      font: { align: 'middle' },
      width: 2,
    },
    {
      id: 'e_relation',
      from: r.subjectId,
      to: r.objectId,
      label: r.predicateType,
      arrows: 'to',
      color: { color: r.polarity ? '#22c55e' : '#ef4444' },
      font: { align: 'middle' },
      width: 3,
    },
    ...(hasChallenger
      ? [
          {
            id: 'e_challenger',
            from: 'challenger_node',
            to: r.objectId,
            label: 'CHALLENGE',
            arrows: 'to',
            dashes: true,
            color: { color: '#f97316' },
            width: 2,
            font: { align: 'middle' },
          },
        ]
      : []),
  ]

  const options: any = {
    physics: false,
    layout: {
      hierarchical: {
        enabled: true,
        direction: 'LR',
        levelSeparation: 160,
        nodeSpacing: 120,
      },
    },
    interaction: {
      dragNodes: true,
      dragView: true,
      zoomView: true,
    },
  }

  // Keep DataSets in sync so we can animate by updating DataSet items
  resetDataSets(nodes, edges)

  if (!network) {
    network = new Network(container.value, { nodes: nodesDS!, edges: edgesDS! }, options)
  } else {
    network.setData({ nodes: nodesDS!, edges: edgesDS! })
    network.setOptions(options)
  }

  // v9: highlight animation when state changes or forced flash
  const renderKey = `${slotId}::${d?.type ?? ''}::${d?.claim?.epistemicStatus ?? ''}::${confNum}`
  const shouldFlash = flash || renderKey !== lastRenderKey
  lastRenderKey = renderKey

  if (shouldFlash) {
    pulseEdges(isConfirmed, isOverridden)
  }
}

function pulseEdges(isConfirmed: boolean, isOverridden: boolean) {
  if (!network) return

  // 高亮色：confirmed=蓝紫；overridden=橙；others=灰
  const hi = isConfirmed ? '#7c3aed' : isOverridden ? '#f97316' : '#64748b'

  // 1) DOMINANT 边闪烁
  updateEdge('e_dominant', {
    width: 6,
    dashes: false,
    color: { color: hi },
  })

  // 2) predicate 边也强调一下（更像“认知落点”）
  updateEdge('e_relation', {
    width: 5,
    color: { color: hi },
  })

  // 🔥 v10 challenger pulse (only if exists)
  updateEdge('e_challenger', {
    width: 6,
    color: { color: '#fb923c' },
  })

  // 回退
  window.setTimeout(() => {
    if (!network) return
    const r = relation.value
    if (!r) return
    updateEdge('e_dominant', {
      width: 2,
      dashes: true,
      color: { color: '#9ca3af' },
    })
    updateEdge('e_relation', {
      width: 3,
      color: { color: r.polarity ? '#22c55e' : '#ef4444' },
    })
    // rollback challenger edge
    updateEdge('e_challenger', {
      width: 2,
      color: { color: '#f97316' },
    })
  }, 520)
}

let unsubscribe: (() => void) | null = null

onMounted(() => {
  // 首次 push 一次（避免 history 空）
  pushSnapshotFromLive()
  renderGraph()

  unsubscribe = store.$subscribe(() => {
    // 任何 store 更新：记录一次快照 + live 渲染（如果当前是 live）
    pushSnapshotFromLive()
    if (activeIndex.value < 0) renderGraph(true)
  })
})

/**
 * 🔄 v9 Live Refresh Mechanism
 * 每次 decision / relation 变化时：
 * 1️⃣ push 新 snapshot
 * 2️⃣ 强制重新渲染 Inspector + Graph
 */
watch(
  () => [liveDecision.value, liveRelation.value],
  () => {
    pushSnapshotFromLive()
    renderGraph(true)
  },
  { deep: true }
)

onBeforeUnmount(() => {
  stopPlay()
  try {
    unsubscribe?.()
  } catch {}
  unsubscribe = null

  if (network) {
    network.destroy()
    network = null
    nodesDS = null
    edgesDS = null
  }
})
</script>

<template>
  <div>
    <!-- 🧠 Cognitive Graph -->
    <div ref="container" style="height:400px;border:1px solid #ccc;"></div>

    <!-- hint -->
    <div style="margin-top:8px;color:#666;font-size:12px">
      [Slot] —dominant→ [Subject/User] —predicate→ [Object]
      <span v-if="activeIndex>=0" style="margin-left:10px;padding:2px 6px;border-radius:6px;background:#eef2ff;color:#3730a3">
        REPLAY MODE ({{ activeIndex + 1 }}/{{ history.length }})
      </span>
      <span v-else style="margin-left:10px;padding:2px 6px;border-radius:6px;background:#ecfeff;color:#0f766e">
        LIVE
      </span>
    </div>

    <!-- 🧠 Cognitive Inspector -->
    <div style="margin-top:16px;padding:16px;border:1px solid #ddd;background:#fafafa;border-radius:8px">
      <h3 style="margin-bottom:10px">🧠 Cognitive Inspector</h3>

      <!-- Decision Status -->
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px">
        <b>Decision:</b>
        <span
          style="padding:2px 8px;border-radius:6px;font-size:12px;"
          :style="{
            background:
              decision?.type==='CONFIRMED'
                ? '#dbeafe'
                : decision?.type==='OVERRIDDEN'
                ? '#ffedd5'
                : '#e5e7eb',
            color:'#111827'
          }"
        >
          {{ decision?.type ?? 'NONE' }}
        </span>
      </div>

      <!-- Dominant Claim -->
      <div
        v-if="decision?.claim"
        style="padding:12px;border:1px solid #e5e7eb;border-radius:8px;background:white;margin-bottom:12px"
      >
        <div style="font-weight:600;margin-bottom:6px">🔥 Dominant Claim（五元组）</div>

        <div>主语(Subject): {{ decision.claim.subjectId }}</div>
        <div>谓语(Predicate): {{ decision.claim.predicate }}</div>
        <div>宾语(Object): {{ decision.claim.objectId }}</div>
        <div>认知状态(epistemicStatus): {{ decision.claim.epistemicStatus ?? '-' }}</div>
        <div>是否肯定(polarity): 
          <span
            v-if="typeof decision.claim.polarity === 'boolean'"
            :class="[
              'pill',
              decision.claim.polarity ? 'pill-positive' : 'pill-negative'
            ]"
            style="font-weight:700;font-size:14px;"
          >
            {{ decision.claim.polarity ? 'TRUE' : 'FALSE' }}
          </span>
        </div>

        <!-- v9 Energy Meter -->
        <div style="margin-top:10px">
          <div style="font-size:12px;color:#666;margin-bottom:4px">
            Confidence:
            {{
              typeof decision.claim.confidence === 'number'
                ? (Math.floor(decision.claim.confidence * 1000) / 1000).toFixed(3)
                : '-'
            }}
          </div>

          <div
            style="
              height:10px;
              background:#e5e7eb;
              border-radius:999px;
              overflow:hidden;
              position:relative;
            "
          >
            <div
              style="
                height:100%;
                transition:width .35s ease, background .3s;
                border-radius:999px;
              "
              :style="{
                width:
                  typeof decision.claim.confidence === 'number'
                    ? (decision.claim.confidence * 100) + '%'
                    : '0%',
                background:
                  decision.type === 'CONFIRMED'
                    ? '#3b82f6'
                    : decision.type === 'OVERRIDDEN'
                    ? '#f97316'
                    : '#9ca3af',
                boxShadow:
                  decision.claim.momentum && decision.claim.momentum > 0
                    ? '0 0 12px rgba(59,130,246,0.6)'
                    : 'none'
              }"
            ></div>

            <div
              v-if="decision.claim.momentum && decision.claim.momentum > 0"
              style="
                position:absolute;
                inset:0;
                background:linear-gradient(
                  90deg,
                  transparent,
                  rgba(255,255,255,0.5),
                  transparent
                );
                animation: cognitivePulse 1.6s linear infinite;
              "
            ></div>
          </div>
        </div>
      </div>

      <!-- Challenger (optional) -->
      <div
        v-if="challenger"
        style="padding:12px;border:1px dashed #f97316;border-radius:8px;background:#fff7ed;margin-bottom:12px"
      >
        <div style="font-weight:600;margin-bottom:6px">⚔️ Challenger Claim</div>
        <div>Subject: {{ challenger.subjectId }}</div>
        <div>Predicate: {{ challenger.predicate }}</div>
        <div>Object: {{ challenger.objectId }}</div>
      </div>

      <div v-if="!decision?.claim" style="color:#999">
        No dominant claim yet (send a message to /chat/personal).
      </div>

      <!-- Reason -->
      <div
        v-if="decision?.reason"
        style="margin-top:10px;padding-left:10px;border-left:3px solid #9ca3af;color:#444"
      >
        Reason: {{ decision.reason }}
      </div>

      <!-- ================================================= -->
      <!-- 🧠 v9 Cognitive Timeline (Interview Highlight)     -->
      <!-- ================================================= -->
      <div style="margin-top:16px;padding-top:12px;border-top:1px dashed #e5e7eb">
        <div style="font-weight:600;margin-bottom:8px">🕒 Cognitive Timeline</div>

        <!-- controls -->
        <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px">
          <button @click="setActive(-1)" style="padding:4px 10px;border:1px solid #ddd;border-radius:6px;background:white">
            Live
          </button>
          <button
            @click="togglePlay"
            style="padding:4px 10px;border:1px solid #ddd;border-radius:6px;background:white"
            :disabled="history.length===0"
          >
            {{ isPlaying ? 'Pause' : 'Play' }}
          </button>

          <div style="display:flex;align-items:center;gap:6px;color:#555;font-size:12px">
            Speed(ms):
            <input
              type="number"
              v-model.number="playSpeedMs"
              min="350"
              max="5000"
              step="50"
              style="width:90px;padding:3px 6px;border:1px solid #ddd;border-radius:6px"
              @change="onSpeedChange"
            />
          </div>

          <div style="flex:1;min-width:260px">
            <input
              v-if="history.length>0"
              type="range"
              :min="0"
              :max="history.length-1"
              :value="activeIndex<0? 0 : activeIndex"
              @input="(e:any)=>setActive(Number(e.target.value))"
              style="width:100%"
            />
          </div>
        </div>

        <!-- list -->
        <div v-if="history.length===0" style="color:#999">No timeline snapshots yet.</div>

        <div v-else style="max-height:180px;overflow:auto;border:1px solid #eee;border-radius:8px;background:white">
          <div
            v-for="(h, idx) in history"
            :key="h.key"
            @click="setActive(idx)"
            style="padding:10px 12px;border-bottom:1px solid #f1f5f9;cursor:pointer;display:flex;gap:10px;align-items:flex-start"
            :style="{
              background: idx===activeIndex ? '#eef2ff' : 'transparent'
            }"
          >
            <div style="min-width:76px;font-size:12px;color:#64748b">
              #{{ idx+1 }}
            </div>
            <div style="flex:1">
              <div style="font-size:12px;color:#334155">
                <b>{{ h.at }}</b>
              </div>
              <div style="font-size:12px;color:#475569;margin-top:2px">
                {{ h.note }}
              </div>
            </div>
          </div>
        </div>

        <div style="margin-top:6px;font-size:12px;color:#64748b">
          Tip: Click timeline items to replay. Graph will “pulse” on each state.
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
@keyframes cognitivePulse {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}

.pill {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
}

.pill-positive {
  color: #3b82f6;
}

.pill-negative {
  color: red;
}

</style>