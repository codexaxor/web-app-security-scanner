# fixtures/eol-springboot-old
Synthetic Maven project pinned to a long-dead Spring Boot line (2.1.5, EOL since 2019) and Java 8. The M6 EOL
detector emits `spring-boot` cycle `2.1` and `eclipse-temurin` cycle `8`; with the eol_cycle cache populated
these raise EOL issues. Used by `EolDetectionIT` and `EolSolvabilityIT`. Not a real project.
