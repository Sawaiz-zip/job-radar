package com.jobradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JobRadarApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobRadarApplication.class, args);
    }
}
