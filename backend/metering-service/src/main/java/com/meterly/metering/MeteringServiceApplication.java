package com.meterly.metering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.meterly.metering", "com.meterly.platform"})
public class MeteringServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeteringServiceApplication.class, args);
    }
}
