package com.yef.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import java.io.IOException;

@EnableAsync
@SpringBootApplication
public class AgentApplication {


    public static void main(String[] args) throws IOException {
        SpringApplication.run(AgentApplication.class, args);
    }

}
