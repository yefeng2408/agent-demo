package com.yef.agent.memory.selfHealing.async;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.event.EpistemicEventType;
import com.yef.agent.memory.selfHealing.*;
import com.yef.agent.memory.selfHealing.eum.ClaimRelationType;
import com.yef.agent.memory.selfHealing.mutation.ConfidenceAdjust;
import com.yef.agent.memory.selfHealing.mutation.RelationAttach;
import com.yef.agent.memory.selfHealing.mutation.StatusOverride;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.util.List;
import static org.neo4j.driver.Values.parameters;

/**
 * HandleEpistemicEventAsyncTask 做的不是确定状态，而是“可解释、可演化”，像 StatusTransition 一样
 *  Self-Healing 的核心目的不是“消灭冲突”，而是：
 * 	•	把“冲突”显式化
 * 	•	把“为什么会冲突”结构化
 * 	•	把“哪一条更可能成为主导”留下 hint
 * 	•	把“系统是如何一步步倾向某个结论的”记录下来
 *
 * “什么时候不触发自我修复”——————最重要（先写死一版规则）
 *  不触发自我修复的情况如下：
 * 	•	不是 OPPOSE（support/neutral 不修复）
 * 	•	slot 内 claim 少于 2 条（没有冲突）
 * 	•	newClaim 和 oldClaim polarity 相同（不是互斥）
 * 	•	newClaim 还是 UNKNOWN 且 confidence 太低（比如 < 0.25）——避免一句话就乱修
 * 	•	oldClaim 仍然 CONFIRMED 且 confidence 极高（比如 > 0.90）——避免瞬间推翻
 *
 */

@Component
public class HandleEpistemicEventAsyncTask {

    private final KeyCodec keyCodec;
    private final ClaimSlotQuery claimSlotQuery;
    private final SelfHealingService selfHealingService;
    private final Driver driver;

    public HandleEpistemicEventAsyncTask(KeyCodec keyCodec,
                                         Driver driver,
                                         ClaimSlotQuery claimSlotQuery,
                                         SelfHealingService selfHealingService
                                         ) {
        this.keyCodec = keyCodec;
        this.driver = driver;
        this.claimSlotQuery = claimSlotQuery;
        this.selfHealingService = selfHealingService;

    }

    /** ✅ 异步消费：persistEpistemicEvent() 之后调用它 */
    @Async
    public void handle(String userId, EpistemicEvent event) {

        // 0) 不关心 SUPPORT（v1），只修复 OPPOSE
        if (event.type() != EpistemicEventType.OPPOSE) {
            return;
        }

        // 1) triggerKey -> newClaim 的 slot
        var decoded = keyCodec.decode(event.triggerKey());

        // 2) 查同 slot 所有 candidates（true/false 都要）
        List<ClaimEvidence> slotClaims = claimSlotQuery.findAllInSlot(
                userId,
                decoded.subjectId(),
                decoded.predicate(),
                decoded.objectId(),
                decoded.quantifier()
        );

        if (slotClaims.size() < 2) {
            return; // 没有冲突，不修
        }


        // 3) 选出 newClaim（就是 trigger polarity 对应那条）以及 existing（其余）
        ClaimEvidence newClaim = slotClaims.stream()
                .filter(c -> c.polarity() == decoded.polarity())
                .findFirst()
                .orElse(null);

        if (newClaim == null) {
            // 说明 triggerKey 指向的 claim 没落库（不应该），直接退出
            return;
        }

        List<ClaimEvidence> existingClaims = slotClaims.stream()
                .filter(c -> c.polarity() != decoded.polarity())
                .toList();

        if (existingClaims.isEmpty()) {
            return;
        }

        // 4) v1：触发条件（保守）
        if (!shouldTrigger(newClaim, existingClaims)) {
            return;
        }

        selfHealingService.handle(event);


    }

    private boolean shouldTrigger(ClaimEvidence newClaim, List<ClaimEvidence> existingClaims) {
        double newConf = newClaim.confidence();
        EpistemicStatus newStatus = newClaim.epistemicStatus() == null ? EpistemicStatus.UNKNOWN : newClaim.epistemicStatus();

        // v1：太弱不修
        if (newConf < 0.25 && newStatus == EpistemicStatus.UNKNOWN) return false;

        // 如果旧方仍然很强（极高置信），也先不修（避免一句话推翻）
        boolean oldVeryStrong = existingClaims.stream()
                .anyMatch(o -> o.confidence() > 0.90 && o.epistemicStatus() == EpistemicStatus.CONFIRMED);

        return !oldVeryStrong;
    }



}