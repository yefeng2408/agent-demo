package com.yef.agent.memory.intent;

public enum EpistemicIntent {
   /* NORMAL,   //普通陈述
    SELF_CORRECTION,    // ⭐ 用户推翻自己
    ASSERT_STRONG      // 可选：强断言。强烈坚持当前观点*/

   SELF_CORRECTION,    //   用户推翻旧观点
    ASSERT_STRONG ,   //   强断言
    WEAK_ASSERT,     //   普通支持
    HEDGE,          //   保留态度
    DOUBT,         //   表示怀疑
    NORMAL        //   中性陈述

}