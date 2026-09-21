package com.meterly.metering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MeteringServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeteringServiceApplication.class, args);
    }
}
