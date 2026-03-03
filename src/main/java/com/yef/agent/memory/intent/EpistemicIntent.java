package com.yef.agent.memory.intent;

/**
 * Intent 类型（语气类别）
 */
public enum EpistemicIntent {

    SELF_CORRECTION,   //   用户推翻旧观点
    ASSERT_STRONG ,   //   强断言
    WEAK_ASSERT,     //   普通支持
    HEDGE,          //   保留态度
    DOUBT,         //   表示怀疑
    NORMAL        //   中性陈述

}