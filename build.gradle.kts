plugins {
    java
}

allprojects {
    group = "com.company.secscan"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Reproducible dependency resolution: commit the lockfiles (see CLAUDE.md).
    dependencyLocking {
        lockAllConfigurations()
    }
}
