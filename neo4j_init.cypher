// ===== Constraints =====
CREATE CONSTRAINT user_userId IF NOT EXISTS
FOR (u:User) REQUIRE u.userId IS UNIQUE;

CREATE CONSTRAINT concept_name IF NOT EXISTS
FOR (c:Concept) REQUIRE c.name IS UNIQUE;

CREATE CONSTRAINT role_name IF NOT EXISTS
FOR (r:Role) REQUIRE r.name IS UNIQUE;

CREATE CONSTRAINT brand_name IF NOT EXISTS
FOR (b:Brand) REQUIRE b.name IS UNIQUE;

CREATE CONSTRAINT claim_claimId IF NOT EXISTS
FOR (c:Claim) REQUIRE c.claimId IS UNIQUE;

CREATE CONSTRAINT evidence_evidenceId IF NOT EXISTS
FOR (e:Evidence) REQUIRE e.evidenceId IS UNIQUE;


// ===== Indexes (optional but useful) =====
CREATE INDEX claim_predicate IF NOT EXISTS
FOR (c:Claim) ON (c.predicate);

CREATE INDEX claim_subject IF NOT EXISTS
FOR (c:Claim) ON (c.subjectId);


// ===== Seed Concepts =====
MERGE (:Concept {name:"ANY_CAR"});
MERGE (:Concept {name:"ANY_ROLE"});

// ===== Optional: seed predicates whitelist as data (not required, but helpful) =====
MERGE (:Predicate {name:"OWNS"});
MERGE (:Predicate {name:"HAS_ROLE"});
MERGE (:Predicate {name:"PURCHASED_AT"});
MERGE (:Predicate {name:"NAME"});
MERGE (:Predicate {name:"BORN_YEAR"});