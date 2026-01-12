package com.yef.agent.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Repository
public class BeliefStore {

    public static final RowMapper<BeliefRow> beliefRowMapper = (rs, rowNum) ->
            new BeliefRow(
                    rs.getLong("id"),
                    rs.getString("user_id"),
                    rs.getString("proposition"),
                    rs.getString("surface"),
                    rs.getString("epistemic_status"),
                    rs.getDouble("confidence"),
                    rs.getTimestamp("updated_at").toLocalDateTime(),
                    rs.getTimestamp("created_at").toLocalDateTime()
            );

    private final JdbcTemplate jdbc;

    public BeliefStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BeliefRow(
            long id,
            String userId,
            String proposition,
            String surface,
            String status,
            double confidence,
            LocalDateTime updatedAt,
            LocalDateTime createdAt
    ) {}

    public Optional<BeliefRow> findByUserAndProposition(String userId, String proposition) {
        var list = jdbc.query(
                "select id, user_id, proposition, surface, epistemic_status, confidence, updated_at,created_at from belief_state where user_id=? and proposition=?",
                (rs, i) -> mapBelief(rs),
                userId, proposition
        );
        return list.stream().findFirst();
    }

    public BeliefRow upsertBelief(String userId, String proposition, String surface, String status, double confidence) {
        LocalDateTime now = LocalDateTime.now();
        Optional<BeliefRow> existed = findByUserAndProposition(userId, proposition);
        if (existed.isPresent()) {
            jdbc.update("""
                update belief_state
                   set surface=?, epistemic_status=?, confidence=?, updated_at=?,created_at=?
                 where user_id=? and proposition=?
                """,
                    surface, status, bd(confidence), now, now,userId, proposition
            );
            return findByUserAndProposition(userId, proposition).orElseThrow();
        }
        jdbc.update("""
    insert into belief_state
    (user_id, proposition, surface, epistemic_status, confidence, updated_at, created_at)
    values (?, ?, ?, ?, ?, ?, ?)
    """,
                userId,
                proposition,
                surface,
                status,
                bd(confidence),
                now,
                now
        );
        return findByUserAndProposition(userId, proposition).orElseThrow();
    }

    public void insertEvidence(long beliefId, String userId, String evidenceType,
                               String modality, String rawText, double confidence) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
            insert into belief_evidence(belief_id, user_id, evidence_type, 
                                        modality, raw_text, confidence, created_at)
            values (?,?,?,?,?,?,?)
            """,
                beliefId, userId, evidenceType, modality, rawText, bd(confidence), now
        );
    }

    private static BeliefRow mapBelief(ResultSet rs) throws java.sql.SQLException {
        return new BeliefRow(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("proposition"),
                rs.getString("surface"),
                rs.getString("epistemic_status"),
                rs.getBigDecimal("confidence").doubleValue(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(3, java.math.RoundingMode.HALF_UP);
    }
}
