//package com.yef.agent.memory;
//
//import lombok.RequiredArgsConstructor;
//import org.springframework.data.neo4j.core.Neo4jClient;
//import org.springframework.stereotype.Component;
//
//
//@Component
//@RequiredArgsConstructor
//public class BeliefStoreGateway {
//
//    private final Neo4jClient neo4jClient;
//
//    public void linkTransition(String oldId, String newId) {
//
//        neo4jClient.query("""
//            MATCH (old:BeliefState {id:$oldId})
//            MATCH (new:BeliefState {id:$newId})
//            MERGE (old)-[:TRANSITION_TO]->(new)
//        """)
//                .bind(oldId).to("oldId")
//                .bind(newId).to("newId")
//                .run();
//    }
//}