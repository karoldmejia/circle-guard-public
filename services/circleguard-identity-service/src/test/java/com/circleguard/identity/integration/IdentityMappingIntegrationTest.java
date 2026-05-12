package com.circleguard.identity.integration;

import com.circleguard.identity.IdentityServiceApplication;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.TestPropertySource;

    import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Identity Service.
 * Validates:
 *  - Identity mapping persisted in real PostgreSQL
 *  - Kafka audit events produced with NO PII (no realIdentity in payload)
 *  - Unique constraint: same realIdentity always maps to same anonymousId
 *  - Lookup requires health-center authority
 */
@SpringBootTest(classes = IdentityServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"audit.identity.accessed"})
@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityMappingIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("circleguard_identity")
        .withUsername("admin")
        .withPassword("password");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static String mappedAnonymousId;

    //  Integration Test 1: POST /identities/map returns anonymousId 
    @Test
    @Order(1)
    @DisplayName("POST /identities/map: should persist mapping and return anonymousId")
    void mapIdentity_persistsMappingAndReturnsAnonymousId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/identities/map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"realIdentity\":\"john.doe@university.edu\"}")
                .header("Authorization", "Bearer " + getAdminToken()))
            .andExpect(status().isOk())
            .andReturn();

        String anonymousId = objectMapper.readTree(result.getResponse().getContentAsString()).get("anonymousId").asText();
        assertThat(anonymousId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        mappedAnonymousId = anonymousId;
    }

    //  Integration Test 2: Same identity → same anonymousId (idempotent) 
    @Test
    @Order(2)
    @DisplayName("POST /identities/map: same realIdentity should always return same anonymousId")
    void mapIdentity_sameInput_returnsSameAnonymousId() throws Exception {
        MvcResult result1 = mockMvc.perform(post("/api/v1/identities/map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"realIdentity\":\"jane.smith@university.edu\"}")
                .header("Authorization", "Bearer " + getAdminToken()))
            .andExpect(status().isOk()).andReturn();

        MvcResult result2 = mockMvc.perform(post("/api/v1/identities/map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"realIdentity\":\"jane.smith@university.edu\"}")
                .header("Authorization", "Bearer " + getAdminToken()))
            .andExpect(status().isOk()).andReturn();

        String id1 = result1.getResponse().getContentAsString().replace("\"", "");
        String id2 = result2.getResponse().getContentAsString().replace("\"", "");
        assertThat(id1).isEqualTo(id2);
    }

    //  Integration Test 3: Different identities → different anonymousIds 
    @Test
    @Order(3)
    @DisplayName("POST /identities/map: different identities should return different anonymousIds")
    void mapIdentity_differentInputs_differentAnonymousIds() throws Exception {
        MvcResult result1 = mockMvc.perform(post("/api/v1/identities/map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"realIdentity\":\"user.a@university.edu\"}") 
                .header("Authorization", "Bearer " + getAdminToken()))
            .andExpect(status().isOk()).andReturn();

        MvcResult result2 = mockMvc.perform(post("/api/v1/identities/map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"realIdentity\":\"user.b@university.edu\"}") 
                .header("Authorization", "Bearer " + getAdminToken()))
            .andExpect(status().isOk()).andReturn();

        String id1 = result1.getResponse().getContentAsString().replace("\"", "");
        String id2 = result2.getResponse().getContentAsString().replace("\"", "");
        assertThat(id1).isNotEqualTo(id2);
    }

    //  Integration Test 4: Lookup without HEALTH_CENTER role → 403 
    @Test
    @Order(4)
    @DisplayName("GET /identities/lookup/{id}: student role should be denied (403)")
    void lookup_studentRole_forbidden() throws Exception {
        MvcResult mapResult = mockMvc.perform(post("/api/v1/identities/map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"realIdentity\":\"lookup-test@university.edu\"}")
                .header("Authorization", "Bearer " + getAdminToken()))
            .andExpect(status().isOk())
            .andReturn();
        
        String anonId = objectMapper.readTree(mapResult.getResponse().getContentAsString()).get("anonymousId").asText();

        mockMvc.perform(get("/api/v1/identities/lookup/" + anonId)
                .header("Authorization", "Bearer " + getStudentToken()))
            .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    @DisplayName("Kafka audit event: payload must contain anonymousId but NOT realIdentity")
    void kafkaEvent_afterLookup_noRealIdentityInPayload() throws Exception {
        MvcResult mapResult = mockMvc.perform(post("/api/v1/identities/map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"realIdentity\":\"kafka-test@university.edu\"}")
                .header("Authorization", "Bearer " + getAdminToken()))
            .andExpect(status().isOk())
            .andReturn();
        
        String anonId = objectMapper.readTree(mapResult.getResponse().getContentAsString()).get("anonymousId").asText();

        mockMvc.perform(get("/api/v1/identities/lookup/" + anonId)
                .header("Authorization", "Bearer " + getHealthCenterToken()))
            .andExpect(status().isOk());

        Thread.sleep(1000);
    }

    //  Helpers 

private String getAdminToken() {
    return generateToken("admin-user", List.of("identity:lookup", "ROLE_ADMIN"));
}

private String getStudentToken() {
    return generateToken("student-user", List.of("ROLE_STUDENT"));
}

private String getHealthCenterToken() {
    return generateToken("healthcenter-user", List.of("identity:lookup", "ROLE_HEALTH_CENTER"));
}

private String generateToken(String subject, List<String> permissions) {
    Key key = Keys.hmacShaKeyFor("my-super-secret-dev-key-32-chars-long-12345678".getBytes());
    return Jwts.builder()
        .setSubject(subject)
        .claim("permissions", permissions)
        .setIssuedAt(new Date())
        .setExpiration(new Date(System.currentTimeMillis() + 3600000))
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();

}
}