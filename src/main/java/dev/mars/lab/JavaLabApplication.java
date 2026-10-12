package dev.mars.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@org.springframework.boot.context.properties.EnableConfigurationProperties(dev.mars.lab.ai.AiProperties.class)
public class JavaLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(JavaLabApplication.class, args);
    }
}
