package com.yef.agent.graph.extract;


import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.memory.vo.CanonicalRelation;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class DefaultRelationCanonicalizer implements RelationCanonicalizer {

    private static final Pattern TESLA = Pattern.compile("特斯拉|tesla", Pattern.CASE_INSENSITIVE);

    @Override
    public Optional<CanonicalRelation> canonicalize(ExtractedRelation r, String msg) {
        if (r == null) return Optional.empty();

        String subjectId = normId(r.subjectId());
        PredicateType predicate = r.predicateType();

        boolean polarity = r.polarity();
        Quantifier quantifier = r.quantifier();

        // ✅ 兜底：否定句 → ONE
        if (!polarity) {
            quantifier = Quantifier.ONE;
        }

        // ✅ objectId 归一
        String objectId = normObjectId(r.objectId(), predicate, msg);
        if (objectId == null) {
            return Optional.empty(); // 禁止写图，避免污染
        }

        return Optional.of(new CanonicalRelation(
                subjectId,
                predicate,
                objectId,
                quantifier,
                polarity,
                clamp01(r.confidence()),
                r.source()
        ));
    }

    private String normObjectId(String objectId, PredicateType predicate, String msg) {
        String o = normId(objectId);

        // 1) 禁止 any 抽象
        if (o == null || o.endsWith(":any") || o.endsWith("|any") || o.contains("any")) {
            o = null;
        }

        // 2) 如果 OWNS 且 object 漂移，尝试从 msg 兜底实体
        if (o == null && predicate == PredicateType.OWNS) {
            if (msg != null && TESLA.matcher(msg).find()) {
                o = "BRAND:Tesla";
            }
        }

        // 3) 仍失败 → null
        return o;
    }

    private String normId(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        // 常见清洗：统一大小写、去全角空格等（你可后续补）
        return t;
    }

    private double clamp01(double x) {
        if (x < 0) return 0;
        if (x > 1) return 1;
        return x;
    }
}