package com.yef.agent.component;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import org.springframework.stereotype.Component;

@Component
public class KeyCodec {

    public String buildEvidenceKey(ClaimEvidence e) {
        return String.join("|",
                e.subjectId(),
                e.predicate().name(),
                e.objectId(),
                e.quantifier().name(),
                String.valueOf(e.polarity())
        );
    }

    public String buildExtractRelKey(ExtractedRelation e) {
        return String.join("|",
                e.subjectId(),
                e.predicateType().name(),
                e.objectId(),
                e.quantifier().name(),
                String.valueOf(e.polarity())
        );
    }

    public String buildCitationKey(Citation c) {
        return String.join("|",
                c.subjectId(),
                c.predicate(),
                c.objectId(),
                c.quantifier(),
                String.valueOf(c.polarity())
        );
    }

    public DecodedKey decode(String claimKey) {
        String[] parts = claimKey.split("\\|");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid claimKey: " + claimKey);
        }

        return new DecodedKey(
                parts[0],
                PredicateType.valueOf(parts[1]),
                parts[2],
                Quantifier.valueOf(parts[3]),
                Boolean.parseBoolean(parts[4])
        );
    }

    public record DecodedKey(
            String subjectId,
            PredicateType predicate,
            String objectId,
            Quantifier quantifier,
            boolean polarity
    ) {}

}
