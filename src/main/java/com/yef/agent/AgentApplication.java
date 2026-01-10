package com.yef.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;

@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) throws IOException {
        System.out.println("Milvus 端口探测开始...");
        new java.net.Socket("127.0.0.1", 19530).close();
        System.out.println("Milvus 端口 19530 已就绪！");
        SpringApplication.run(AgentApplication.class, args);
    }

}
