package com.example.amrkpi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AmrKpiApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(AmrKpiApplication.class, args);
        } catch (Throwable startupFailure) {
            // Some startup-failure paths (e.g. every DefaultAzureCredential in the chain
            // unavailable) leave non-daemon threads running in dependencies (observed with
            // azure-identity's reactor schedulers), so the JVM never exits on its own even
            // though the main thread died. Force it — otherwise Kubernetes never sees the pod
            // exit and can't restart it; it just hangs as an unready zombie forever.
            System.exit(1);
        }
    }
}
