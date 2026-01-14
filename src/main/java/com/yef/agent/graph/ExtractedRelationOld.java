/*
package com.yef.agent.graph;

import com.yef.agent.graph.eum.ObjectType;
import com.yef.agent.graph.eum.PredicateType;
import lombok.Data;
import lombok.ToString;

*/
/**
 * v3 唯一允许从 LLM 输出的“关系事实”
 *
 * 语义含义：
 *   subject --(predicate + polarity)--> object
 *//*

@Data
@ToString
public class ExtractedRelation {

    */
/**
     * 是否成功匹配到一个“允许写入图”的关系
     * false = 本轮对话不产生长期记忆（完全正常）
     *//*

    public boolean matched;

    */
/**
     * 谓词（硬约束，enum）
     * 例如：NAME / HAS_ROLE / OWNS
     *//*

    public PredicateType predicate;

    */
/**
     * 客体类型（硬约束，enum）
     * 例如：CAR / BRAND / ROLE / YEAR
     *//*

    public ObjectType objectType;

    */
/**
     * 客体值（字符串）
     * 例如：Tesla / any / BackendDeveloper / 1992
     *//*

    public String objectValue;

    */
/**
     * 极性
     * true  = 肯定（是 / 有 / 属于）
     * false = 否定（不是 / 没有 / 从未）
     *//*

    public boolean polarity;

    */
/**
     * 置信度（0~1）
     * 表示 LLM 对“这是一条长期事实”的把握程度
     *//*

    public double confidence;
}*/
