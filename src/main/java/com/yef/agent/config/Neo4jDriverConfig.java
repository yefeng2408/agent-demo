package com.yef.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class Neo4jDriverConfig {


    @Bean
    public Driver neo4jDriver(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String username,
            @Value("${neo4j.password}") String password
    ) {
        log.info("Neo4j Driver config url={}  username={}  password={}", uri,username, password);
        Config config = Config.builder()
                .withoutEncryption()   // 等价于 encrypted=false
                .build();

        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }
}