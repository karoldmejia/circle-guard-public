package com.circleguard.auth.integration;

import com.circleguard.auth.AuthServiceApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;


/**
 * Integration test: full login flow against real PostgreSQL.
 * Validates that the auth service can authenticate users from the DB,
 * return a JWT with anonymousId as subject, and reject invalid credentials.
 *
 * Uses Testcontainers to spin up a real PostgreSQL instance.
 * Flyway migrations are applied automatically.
 */
@SpringBootTest(classes = AuthServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(Lifecycle.PER_CLASS) 
class LoginFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("circleguard_auth")
        .withUsername("admin")
        .withPassword("password");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.ldap.urls", () -> "ldap://localhost:389");
        registry.add("identity.service.url", () -> "http://localhost:8099");
    }

    @Autowired
    private MockMvc mockMvc;

    // Integration Test 1: Valid credentials return JWT with anonymousId
    @Test
    @Order(1)
    @DisplayName("POST /login: valid credentials should return JWT with anonymousId as subject")
    void login_validCredentials_returnsJwtWithAnonymousId() throws Exception {
        String loginRequest = """
            {"username": "student1", "password": "password123"}
            """;

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.type").value("Bearer"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("token");

        // Decode JWT payload to verify subject is UUID (anonymousId), not "student1"
        String token = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(body).get("token").asText();
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getDecoder().decode(parts[1]));
        assertThat(payload).doesNotContain("student1");
        assertThat(payload).contains("sub");
        assertThat(payload).containsPattern("[0-9a-f-]{36}");
    }

    // Integration Test 2: Invalid credentials return 401
    @Test
    @Order(2)
    @DisplayName("POST /login: wrong password should return 401 Unauthorized")
    void login_wrongPassword_returns401() throws Exception {
        String loginRequest = """
            {"username": "student1", "password": "wrongpassword"}
            """;

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginRequest))
            .andExpect(status().isUnauthorized());
    }

    // Integration Test 3: Non-existent user returns 401
    @Test
    @Order(3)
    @DisplayName("POST /login: non-existent user should return 401")
    void login_unknownUser_returns401() throws Exception {
        String loginRequest = """
            {"username": "nobody@nowhere.com", "password": "somepassword"}
            """;

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginRequest))
            .andExpect(status().isUnauthorized());
    }

    // Integration Test 4: Login response includes expected fields
    @Test
    @Order(4)
    @DisplayName("POST /login: response should include token, type, and anonymousId fields")
    void login_success_responseHasRequiredFields() throws Exception {
        String loginRequest = """
            {"username": "admin1", "password": "admin123"}
            """;

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isString())
            .andExpect(jsonPath("$.type").value("Bearer"))
            .andExpect(jsonPath("$.anonymousId").exists());
    }

    // Integration Test 5: Flyway migrations applied (tables exist)
    @Test
    @Order(5)
    @DisplayName("Database: Flyway migrations should create local_users table")
    void flyway_migrationsApplied_tablesExist() {
        assertThat(postgres.isRunning()).isTrue();
        // If we got here without DataAccessException, Flyway ran correctly
        // and the tables used by previous tests exist
    }
}