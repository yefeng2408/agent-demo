import { defineStore } from 'pinia'
import type { ExtractedRelation } from '../types/ExtractedRelation'


export const useAgentStore = defineStore('agent', {
  state: () => ({
    relation: null as ExtractedRelation | null, // citations: [] as string[],
    citations: [],
    decision: null
  }),

  actions: {
    updateFromAnswerResult(r: any) {
      this.citations = r.citations
        // ⭐ relation
      this.relation = r.relation ?? null
      // ⭐⭐⭐ 关键：写入 decision
      this.decision = r.decision ?? null
    }
  }
})