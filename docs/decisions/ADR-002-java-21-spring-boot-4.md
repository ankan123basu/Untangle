# ADR-002: Java 21 LTS and Spring Boot 4.1

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 0

## Context

The brief asks for Java 21 (LTS) and "Spring Boot latest stable". As of 2026-11-18 the latest GA
Spring Boot is **4.1.2**. Java 25 is also an LTS. The developer's machine has JDK 21 installed.

## Options considered

1. **Java 21 + Spring Boot 3.5.x.** Most tutorials match. But 3.5 is the previous generation and
   moving to 4 later would be a migration (Jackson 3, Security 7, starter renames).
2. **Java 25 + Spring Boot 4.1.** Newest LTS. Differs from the brief and the installed JDK.
3. **Java 21 + Spring Boot 4.1.** Matches the brief and the installed JDK; current Boot generation.

## Decision

Option 3. Bytecode targets Java 21 (`<java.version>21</java.version>`); `maven-enforcer` requires
JDK ≥ 21. Docker uses Temurin 21.0.13. Virtual threads (`spring.threads.virtual.enabled=true`) are on.

## Consequences

- **Pros:** current Spring generation with long support; matches the brief; Java 21 gives records,
  pattern matching, sealed types and virtual threads.
- **Cons:** Boot 4 differs from many Boot 3 tutorials:
  - Starters are modular: `spring-boot-starter-webmvc` (was `-web`), with matching test starters
    such as `spring-boot-starter-webmvc-test`.
  - Test annotations moved, e.g. `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
  - Jackson 3 (`tools.jackson.*` packages).
  - Spring Security 7.
  We verify each API against the jars or official docs before use (working agreement rule 3).
- **Note on Homebrew Maven:** it runs on its own newest JDK. The Makefile sets `JAVA_HOME` to JDK
  21 on macOS so local builds match Docker and CI.

## Simple explanation

We use the Java version the brief asked for, with the newest generation of Spring Boot, so we don't
have to upgrade the framework halfway through the project.
