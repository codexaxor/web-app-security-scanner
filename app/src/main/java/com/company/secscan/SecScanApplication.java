package com.company.secscan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SecScan entry point. The same jar runs as either process, selected by the active Spring profile:
 * {@code --spring.profiles.active=api} or {@code =worker} (see {@code docs/SPEC.md}).
 */
@SpringBootApplication
public class SecScanApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecScanApplication.class, args);
    }
}
