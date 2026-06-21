package com.redis.stockanalysisagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StockAnalysisAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockAnalysisAgentApplication.class, args);
    }

}
