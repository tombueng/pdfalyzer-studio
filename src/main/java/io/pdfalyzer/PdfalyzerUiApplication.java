package io.pdfalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PdfalyzerUiApplication {
    public static void main(String[] args) {
        SpringApplication.run(PdfalyzerUiApplication.class, args);
    }
}
