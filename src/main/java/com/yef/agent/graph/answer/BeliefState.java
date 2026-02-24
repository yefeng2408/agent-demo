package com.yef.agent.graph.answer;

import lombok.Data;
import java.time.Instant;

@Data
public class BeliefState {
    //neo4j生成的id，不是业务id
    String id;
    double confidence;
    int decayLevel;
    String dominantClaimKey;
    Instant lastChallengedAt;
    double momentumP;
    String reason;
    Instant since;
    String slotKey;

}
