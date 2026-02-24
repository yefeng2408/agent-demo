package com.yef.agent.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Neo4jDriverConfig {

    @Bean
    public Driver neo4jDriver(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String username,
            @Value("${neo4j.password}") String password
    ) {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }
}