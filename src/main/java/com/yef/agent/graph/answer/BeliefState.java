package com.yef.agent.graph.answer;

import com.yef.agent.memory.SlotBeliefState;
import lombok.Data;
import java.time.Instant;

@Data
public class BeliefState {

    String slotKey;              //所属冲突域

    SlotBeliefState beliefState; //当前结构状态

    String dominantClaimKey;     // 当前 dominant（可以是nullable）

    Instant since;               //状态开始间

    Instant lastEvaluatedAt;     //最近裁决时间

}
