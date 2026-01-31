package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.pipeline.EpistemicContext;
import java.util.List;

/**
 * A strategy that computes epistemic deltas (i.e. incremental changes)
 * to be applied to claims under a given epistemic context.
 *
 * <p>
 * A {@code Delta} represents a minimal, intentional modification to the
 * epistemic state, rather than a full state replacement.
 * Typical deltas include confidence adjustment, support count update,
 * or epistemic status transition.
 * </p>
 *
 * <p>
 * Implementations of this interface are used by the epistemic delta pipeline
 * to decide <b>what should change</b>, not to directly mutate the state.
 * The actual application of these deltas is handled downstream.
 * </p>
 *
 * <p>
 * This design enables:
 * <ul>
 *   <li>Explainable state transitions</li>
 *   <li>Composable epistemic strategies</li>
 *   <li>Deferred and auditable state mutation</li>
 * </ul>
 * </p>
 */
public interface DeltaStrategy {

    /**
     * Determines whether this strategy is applicable to the given epistemic context.
     */
    boolean supports(EpistemicContext ctx);

    /**
     * Computes a list of claim-level deltas that describe how the epistemic
     * state should evolve under the given context.
     *
     * @return a list of {@link ClaimDelta}, each representing an incremental change
     */
    List<ClaimDelta> apply(EpistemicContext ctx);

    /**
     * Clamps a numeric value into a valid epistemic range.
     */
    double clamp(double v);
}