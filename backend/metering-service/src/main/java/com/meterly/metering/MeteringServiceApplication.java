package com.meterly.metering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.meterly.metering", "com.meterly.platform"})
@EnableScheduling
public class MeteringServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeteringServiceApplication.class, args);
    }
}
