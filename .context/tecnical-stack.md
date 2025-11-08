### Technical Stack — apigw

Generated: 2025-11-08 14:52

---

#### Overview
A lightweight API Gateway project built with Spring Boot and Spring Cloud Gateway (WebMVC variant), targeting Java 21 and managed with Maven. The repository also includes a Postman collection and a human-readable API summary under `.spec/`.

---

#### Languages & Runtime
- Language: Java 21 (`<java.version>21</java.version>` in `pom.xml`)
- Runtime: JVM 21 (Temurin/Oracle/OpenJDK compatible)

---

#### Core Frameworks
- Spring Boot 3.5.7 (parent POM)
- Spring Cloud 2025.0.0 (via `spring-cloud-dependencies` BOM)
- Spring Cloud Gateway — WebMVC variant (`spring-cloud-starter-gateway-server-webmvc`)
  - Note: This is the Servlet-based (blocking) flavor, not the reactive WebFlux gateway.

---

#### Key Libraries (Dependencies)
- `org.springframework.boot:spring-boot-starter-web`
  - Spring MVC, embedded server (Tomcat by default), JSON via Jackson
- `org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc`
  - Gateway routing, filters, predicates on top of MVC stack
- `org.projectlombok:lombok` (optional, annotation processor)
  - Reduces boilerplate (getters/setters/constructors)
- `org.springframework.boot:spring-boot-starter-test` (test scope)
  - JUnit Jupiter, AssertJ, Spring Test utilities

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
  - Add gateway routes and other settings here (e.g., `spring.cloud.gateway.mvc.routes[...]` for WebMVC Gateway)

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
- Gateway Server WebMVC runs on Servlet stack; do not mix with WebFlux Gateway starters

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
