package com.yef.agent.service;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.neo4j.driver.Values.parameters;

@Component
public class MemoryResetService {

    private final Driver driver;
    public MemoryResetService(@Autowired Driver driver) {
        this.driver = driver;
    }


    public void toEmptyUserDataByUserId(String userId) {
        //[*0..5] 代表删除5层以内的所有关联节点
        String cypher = """
                MATCH (u:User {id:$uid})
                OPTIONAL MATCH (u)-[*0..5]-(n)
                DETACH DELETE u, n
                """;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId
            )).consume());
        }
    }
}
