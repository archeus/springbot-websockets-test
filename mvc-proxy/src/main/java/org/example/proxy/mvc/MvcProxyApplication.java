package org.example.proxy.mvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MvcProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MvcProxyApplication.class, args);
    }
}
