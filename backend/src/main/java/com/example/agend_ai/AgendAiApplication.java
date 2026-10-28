package com.example.agend_ai;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgendAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgendAiApplication.class, args);
    }
}
