package com.synapseai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.synapseai.mapper")
@EnableScheduling
public class SynapseAIApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynapseAIApplication.class, args);
    }

}
