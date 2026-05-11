package com.circleguard.notification.integration;

import com.circleguard.notification.NotificationApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * Integration test: Kafka event pipeline.
 * Verifies that when promotion publishes a status-change event to Kafka,
 * the notification service consumes it and dispatches alerts.
 *
 * Uses embedded Kafka and WireMock for auth service mock.
 */
@SpringBootTest(classes = NotificationApplication.class)
@EmbeddedKafka(
    partitions = 1,
    brokerProperties = {"listeners=PLAINTEXT://localhost:29092", "port=29092"},
    topics = {"promotion.status.changed"}
)
@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaConsumptionIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // Track dispatches for verification
    static volatile int dispatchCount = 0;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(8099);
        wireMockServer.start();
        WireMock.configureFor("localhost", 8099);

        // Mock auth service response for user email lookup
        stubFor(get(urlMatching("/api/v1/users/.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    [{"username":"test-user-001","email":"test-user-001@example.com"}]
                    """)));
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:29092");
        registry.add("auth.api.url", () -> "http://localhost:8099");
    }

    //  Integration Test 1: CONFIRMED event is consumed and dispatched 
    @Test
    @Order(1)
    @DisplayName("Kafka: CONFIRMED status event should be consumed and notification dispatched")
    void kafkaConsumer_confirmedEvent_triggersDispatch() throws Exception {
        String event = objectMapper.writeValueAsString(Map.of(
            "anonymousId", "test-user-001",
            "status", "CONFIRMED",
            "timestamp", System.currentTimeMillis()
        ));

        kafkaTemplate.send("promotion.status.changed", "test-user-001", event);

        // Wait up to 10 seconds for the consumer to process the event
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            // Verify WireMock received a call to auth (looking up user email)
            verify(getRequestedFor(urlMatching("/api/v1/users/.*")));
        });
    }

    //  Integration Test 2: ACTIVE event is NOT dispatched 
    @Test
    @Order(2)
    @DisplayName("Kafka: ACTIVE status event should be consumed but NOT dispatched")
    void kafkaConsumer_activeEvent_noDispatch() throws Exception {
        WireMock.resetAllRequests();

        String event = objectMapper.writeValueAsString(Map.of(
            "anonymousId", "test-user-002",
            "status", "ACTIVE",
            "timestamp", System.currentTimeMillis()
        ));

        kafkaTemplate.send("promotion.status.changed", "test-user-002", event);
        Thread.sleep(3000); // Give consumer time to process

        // Auth service should NOT have been called (no dispatch for ACTIVE)
        verify(0, getRequestedFor(urlMatching("/api/v1/users/test-user-002")));
    }

    //  Integration Test 3: SUSPECT event is consumed 
    @Test
    @Order(3)
    @DisplayName("Kafka: SUSPECT event should be consumed and dispatched")
    void kafkaConsumer_suspectEvent_dispatched() throws Exception {
        String event = objectMapper.writeValueAsString(Map.of(
            "anonymousId", "test-user-003",
            "status", "SUSPECT",
            "timestamp", System.currentTimeMillis()
        ));

        kafkaTemplate.send("promotion.status.changed", "test-user-003", event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(atLeastOnce(), getRequestedFor(urlMatching("/api/v1/users/.*")));
        });
    }

    //  Integration Test 4: Malformed event does not kill consumer 
    @Test
    @Order(4)
    @DisplayName("Kafka: malformed event should not crash the consumer (poison pill test)")
    void kafkaConsumer_malformedEvent_doesNotCrashConsumer() throws Exception {
        kafkaTemplate.send("promotion.status.changed", "bad-key", "{ INVALID JSON !!! }");
        Thread.sleep(2000);

        // Send a valid event after – should still be processed
        String validEvent = objectMapper.writeValueAsString(Map.of(
            "anonymousId", "test-user-004",
            "status", "PROBABLE",
            "timestamp", System.currentTimeMillis()
        ));
        kafkaTemplate.send("promotion.status.changed", "test-user-004", validEvent);

        // Consumer should still be alive and process valid event
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(atLeastOnce(), getRequestedFor(urlMatching("/api/v1/users/.*")));
        });
    }

    //  Integration Test 5: Latency from event to dispatch < 3 seconds 
    @Test
    @Order(5)
    @DisplayName("Kafka: event-to-dispatch latency should be under 3 seconds")
    void kafkaConsumer_latency_underThreeSeconds() throws Exception {
        WireMock.resetAllRequests();
        long startTime = System.currentTimeMillis();

        String event = objectMapper.writeValueAsString(Map.of(
            "anonymousId", "test-user-latency",
            "status", "CONFIRMED",
            "timestamp", startTime
        ));

        kafkaTemplate.send("promotion.status.changed", "test-user-latency", event);

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(atLeastOnce(), getRequestedFor(urlMatching("/api/v1/users/.*")));
        });

        long elapsed = System.currentTimeMillis() - startTime;
        Assertions.assertTrue(elapsed < 3000, "Dispatch latency " + elapsed + "ms exceeds 3s SLA");
    }
}