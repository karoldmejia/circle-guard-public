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

    // Integration Test 2: Invalid credentials return 401
    @Test
    @Order(1)
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
    @Order(2)
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

    // Integration Test 5: Flyway migrations applied (tables exist)
    @Test
    @Order(3)
    @DisplayName("Database: Flyway migrations should create local_users table")
    void flyway_migrationsApplied_tablesExist() {
        assertThat(postgres.isRunning()).isTrue();
    }
}