package com.yef.agent.memory;

public enum SlotBeliefState {


    /**
     * 认知状态v2version。 要理解 belief dynamics 是“相对势能”，不是“绝对强度”。
     */

    STABLE_CONFIRMED,     // dominant， 且高置信 【dominant && confidence >= 0.8 && 无 challenger】
    WEAKLY_CONFIRMED,     // dominant， 但信念不稳 【dominant && 0.5 <= confidence < 0.8】
    CONTESTED,            // dominant 存在势均力敌的challenger（confidence 差值很小），结构冲突态；
                            // 换言之：存在 polarity 相反的 challenger，且 |dominant - challenger| <= ε

    OVERTURNED,           // 被推翻 【dominant 被新 claim 取代   confidence< 0.2 】
    //UNFORMED,              // 尚未形成 dominant 【尚未产生 dominant 0.2<=confidence<0.5】
    UNKNOWN;




}
