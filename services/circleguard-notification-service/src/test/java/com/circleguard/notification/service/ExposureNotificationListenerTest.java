package com.circleguard.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExposureNotificationListener.
 * Validates Kafka event parsing and dispatch logic.
 *
 * KEY RULES:
 *  - ACTIVE status should NOT trigger notification (not a risk)
 *  - CONFIRMED/SUSPECT/PROBABLE its goint to triggers dispatch and LMS sync
 *  - if malformed JSON, so logged as error, NOT re-thrown (no poison pill)
 */
@ExtendWith(MockitoExtension.class)
class ExposureNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @Mock private LmsService lmsService;

    @InjectMocks
    private ExposureNotificationListener listener;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(listener, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("handleStatusChange: CONFIRMED status should trigger notification dispatch")
    void handleStatusChange_confirmed_triggersDispatch() {
        String event = """
            {"anonymousId":"user-confirmed-001","status":"CONFIRMED","timestamp":1234567890}
            """;

        listener.handleStatusChange(event);

        verify(dispatcher).dispatch("user-confirmed-001", "CONFIRMED");
    }

    @Test
    @DisplayName("handleStatusChange: ACTIVE status should NOT trigger notification")
    void handleStatusChange_active_noDispatch() {
        String event = """
            {"anonymousId":"user-active-001","status":"ACTIVE","timestamp":1234567890}
            """;

        listener.handleStatusChange(event);

        verify(dispatcher, never()).dispatch(anyString(), anyString());
        verify(lmsService, never()).syncRemoteAttendance(anyString(), anyString());
    }

    @Test
    @DisplayName("handleStatusChange: SUSPECT status should trigger dispatch AND LMS sync")
    void handleStatusChange_suspect_triggersDispatchAndLmsSync() {
        String event = """
            {"anonymousId":"user-suspect-001","status":"SUSPECT"}
            """;

        listener.handleStatusChange(event);

        verify(dispatcher).dispatch("user-suspect-001", "SUSPECT");
        verify(lmsService).syncRemoteAttendance("user-suspect-001", "SUSPECT");
    }

    @Test
    @DisplayName("handleStatusChange: PROBABLE status should trigger notification dispatch")
    void handleStatusChange_probable_triggersDispatch() {
        String event = """
            {"anonymousId":"user-probable-001","status":"PROBABLE"}
            """;

        listener.handleStatusChange(event);

        verify(dispatcher).dispatch("user-probable-001", "PROBABLE");
    }

    @Test
    @DisplayName("handleStatusChange: malformed JSON should be handled gracefully, no re-throw")
    void handleStatusChange_malformedJson_handledGracefully() {
        String badEvent = "{ this is not valid json !!!";

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> listener.handleStatusChange(badEvent)
        );

        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("handleStatusChange: missing anonymousId defaults to 'unknown'")
    void handleStatusChange_missingAnonymousId_defaultsToUnknown() {
        String event = """
            {"status":"CONFIRMED"}
            """;

        listener.handleStatusChange(event);

        // Should dispatch with "unknown" as user ID
        verify(dispatcher).dispatch(eq("unknown"), eq("CONFIRMED"));
    }

    @Test
    @DisplayName("handleStatusChange: UNKNOWN status should not trigger dispatch")
    void handleStatusChange_unknownStatus_noDispatch() {
        String event = """
            {"anonymousId":"user-xyz","status":"UNKNOWN"}
            """;

        listener.handleStatusChange(event);

        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }
}