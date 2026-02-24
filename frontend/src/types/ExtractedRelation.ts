
export interface ExtractedRelation {
  subjectId: string
  predicateType: string
  objectId: string
  quantifier: string
  polarity: boolean
  confidence: number
  source: string
  generation: string
}