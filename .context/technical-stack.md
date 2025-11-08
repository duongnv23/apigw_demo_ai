### Technical Stack — apigw

Generated: 2025-11-08 15:04

---

#### Overview
A lightweight API Gateway built on Spring Boot and Spring Cloud Gateway (Reactive/WebFlux), targeting Java 21 and managed with Maven. The repo also includes a Postman collection and a human‑readable API summary under `.spec/`.

---

#### Languages & Runtime
- Language: Java 21 (`<java.version>21</java.version>` in `pom.xml`)
- Runtime: JVM 21 (Temurin/Oracle/OpenJDK compatible)

---

#### Core Frameworks
- Spring Boot 3.5.7 (parent POM)
- Spring Cloud 2025.0.0 (via `spring-cloud-dependencies` BOM)
- Spring Cloud Gateway — Reactive (`org.springframework.cloud:spring-cloud-starter-gateway`)
- Spring WebFlux (`org.springframework.boot:spring-boot-starter-webflux`)
- Spring Boot Actuator (`org.springframework.boot:spring-boot-starter-actuator`)
- Resilience4j Circuit Breaker for Reactor (`org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j`)

---

#### Key Libraries (Dependencies)
- `org.springframework.cloud:spring-cloud-starter-gateway`
  - Reactive API gateway (Netty server by default via WebFlux)
- `org.springframework.boot:spring-boot-starter-webflux`
  - Reactive web stack based on Project Reactor
- `org.springframework.boot:spring-boot-starter-actuator`
  - Health checks, metrics, monitoring endpoints
- `org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j`
  - Circuit breaker, retry, rate limiting on Reactor
- `org.springframework.boot:spring-boot-starter-validation`
  - Jakarta Bean Validation for request/DTO validation
- `com.fasterxml.jackson.core:jackson-databind`
  - JSON processing (explicitly listed in the POM)
- `org.projectlombok:lombok` (optional, annotation processor)
  - Reduces boilerplate (getters/setters/constructors)
- Testing:
  - `org.springframework.boot:spring-boot-starter-test` (test scope)
  - `io.projectreactor:reactor-test` (test scope)

---

#### Build & Dependency Management
- Build tool: Maven (wrappers provided: `mvnw`, `mvnw.cmd`)
- Parent: `org.springframework.boot:spring-boot-starter-parent:3.5.7`
- BOM: `org.springframework.cloud:spring-cloud-dependencies:${spring-cloud.version}` with `${spring-cloud.version}=2025.0.0`
- Compiler plugin configured to include Lombok in `annotationProcessorPaths`
- Spring Boot Maven Plugin for packaging and running (`spring-boot:run`, fat jar)

---

#### Project Modules & Entry Points
- Single-module application
- Main class: `com.rezo.apigw.ApigwApplication` (`src/main/java/com/rezo/apigw/ApigwApplication.java`)
- Tests: `src/test/java/com/rezo/apigw/ApigwApplicationTests.java`

---

#### Configuration
- Spring Application Properties: `src/main/resources/application.properties`
  - Default profile assumed unless overridden
  - Define gateway routes and filters here (Reactive gateway): `spring.cloud.gateway.routes[0].id`, `spring.cloud.gateway.routes[0].uri`, `spring.cloud.gateway.routes[0].predicates[0]`, etc.
  - Actuator endpoints can be exposed via `management.endpoints.web.exposure.include=health,info,...`

---

#### Testing
- Framework: JUnit 5 (via Spring Boot Starter Test)
- Typical commands:
  - `./mvnw test` — run unit tests
  - `./mvnw -Dtest=... test` — run specific tests

---

#### API & Integration Artifacts
- Postman collection: `data/FCBV_API_VRB.postman_collection.json`
- Human-readable API summary: `.spec/api.md` (created from the Postman collection)

---

#### Packaging & Execution
- Packaging: Executable Spring Boot JAR
- Build: `./mvnw clean package`
- Run (dev): `./mvnw spring-boot:run`
- Run (jar): `java -jar target/apigw-0.0.1-SNAPSHOT.jar`

---

#### Conventions & Tooling
- Source layout: standard Maven (`src/main/java`, `src/test/java`, `src/main/resources`)
- Git setup: `.gitignore`, `.gitattributes` present
- Lombok annotations expected in code (ensure IDE annotation processing is enabled)

---

#### Version/Compatibility Notes
- Spring Boot 3.5.x requires Java 17+; project targets Java 21 (OK)
- Spring Cloud 2025.0.0 is aligned with Boot 3.5.x (managed via BOM)
- Reactive gateway runs on the WebFlux/Netty stack; avoid mixing with Spring MVC (`spring-boot-starter-web`) or the WebMVC gateway starter

---

#### How to Run Locally
1) Prerequisites: JDK 21 and internet access for dependencies
2) Build and test:
```
./mvnw clean verify
```
3) Start the app:
```
./mvnw spring-boot:run
```
4) Configure routes in `application.properties` as needed for the gateway

---

#### Files Referenced
- `pom.xml` — dependency and plugin definitions
- `src/main/java/com/rezo/apigw/ApigwApplication.java` — Spring Boot entry point
- `src/main/resources/application.properties` — application configuration
- `.spec/api.md` — API summary
- `data/FCBV_API_VRB.postman_collection.json` — Postman collection
