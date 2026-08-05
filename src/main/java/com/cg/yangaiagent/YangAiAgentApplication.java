package com.cg.yangaiagent;

import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
public class YangAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(YangAiAgentApplication.class, args);
    }
}
