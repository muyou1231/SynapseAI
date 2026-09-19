package com.synapseai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SynapseAIApplicationTests {

    @Value("${spring.application.name}")
    private String appName;

    @Test
    void contextLoads() {
        System.out.println(appName);
    }

}
