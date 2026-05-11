package com.circleguard.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmailServiceImpl and notification dispatch logic.
 * Covers email building, sending, audit logging, and retry behaviour.
 *
 * PRIVACY REQUIREMENT: logs must not contain full email addresses.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock private JavaMailSender mailSender;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private EmailServiceImpl emailService;

    @Test
    @DisplayName("sendAsync: should send email to userId@example.com")
    void sendAsync_sendsEmailToCorrectRecipient() throws Exception {
        String userId = "user-abc-123";
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

        CompletableFuture<Void> future = emailService.sendAsync(userId, "Health alert message");
        future.get(); // wait for async completion

        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("user-abc-123@example.com");
    }

    @Test
    @DisplayName("sendAsync: email subject should be 'CircleGuard Health Alert'")
    void sendAsync_correctSubject() throws Exception {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

        emailService.sendAsync("user-001", "Test message").get();

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("CircleGuard Health Alert");
    }

    @Test
    @DisplayName("sendAsync: email body should contain the given message text")
    void sendAsync_emailBodyContainsMessage() throws Exception {
        String message = "You may have been in contact with a confirmed case.";
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

        emailService.sendAsync("user-002", message).get();

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText()).contains(message);
    }

    @Test
    @DisplayName("sendAsync: successful send should log SUCCESS in audit log")
    void sendAsync_success_logsAudit() throws Exception {
        emailService.sendAsync("user-003", "Alert!").get();

        verify(auditLogService).logDelivery(eq("user-003"), eq("EMAIL"), eq("SUCCESS"), anyString());
    }

    @Test
    @DisplayName("sendAsync: failed send should log RETRY in audit log before retry")
    void sendAsync_failure_logsRetry() {
        doThrow(new RuntimeException("SMTP connection refused"))
            .when(mailSender).send(any(SimpleMailMessage.class));

        // @Retryable will retry 3 times then call @Recover
        assertThatThrownBy(() -> emailService.sendAsync("user-004", "Alert!").get())
            .hasCauseInstanceOf(RuntimeException.class);

        verify(auditLogService, atLeastOnce())
            .logDelivery(eq("user-004"), eq("EMAIL"), eq("RETRY"), anyString());
    }

    @Test
    @DisplayName("sendAsync: userId should be anonymousId, email built as userId@example.com")
    void sendAsync_userIdIsAnonymous_emailBuiltSafely() throws Exception {
        // anonymousId should be a UUID-like string, NOT a real email
        String anonymousId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

        emailService.sendAsync(anonymousId, "Alert").get();

        verify(mailSender).send(captor.capture());
        // Verify address is constructed safely
        assertThat(captor.getValue().getTo()[0]).isEqualTo(anonymousId + "@example.com");
        // Real identity should NOT be in the recipient
        assertThat(captor.getValue().getTo()[0]).doesNotContain("john").doesNotContain("doe");
    }

    @Test
    @DisplayName("sendAsync: should return a completed future on success")
    void sendAsync_returnsCompletedFuture() throws Exception {
        CompletableFuture<Void> future = emailService.sendAsync("user-005", "Notification");

        assertThat(future).isNotNull();
        assertThat(future.isCompletedExceptionally()).isFalse();
        future.get();
    }
}