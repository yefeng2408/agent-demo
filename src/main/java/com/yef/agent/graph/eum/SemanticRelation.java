package com.yef.agent.graph.eum;

/**
 * 语义判断层（认知判断）
 */
public enum SemanticRelation {

    /**
     * 新输入语义 与 既有主张 同向
     * 例：A=true，新=true
     */
    SUPPORT,

    /**
     * 新输入语义 与 既有主张 反向
     * 例：A=true，新=false
     */
    OPPOSE,

    /**
     * 非声明语义语义 无法比较 / 不相关
     * 例：predicate 不同、object 不同
     */
    NEUTRAL
}