package com.support.agent.service;

import com.support.agent.client.MailpitClient;
import com.support.agent.client.dto.MailpitMessage;
import com.support.agent.client.dto.MailpitMessageSummary;
import com.support.agent.config.InboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxMonitorTest {

    private final MailpitClient mailpit = mock(MailpitClient.class);
    private final EmailHandler handler = mock(EmailHandler.class);
    private final InboxMonitor monitor = new InboxMonitor(mailpit, handler,
            new InboxProperties("http://localhost:8025", "support@example.com", 10000, 50));

    @BeforeEach
    void setUp() {
        MailpitMessageSummary summary = mock(MailpitMessageSummary.class);
        when(summary.id()).thenReturn("m1");
        when(mailpit.listUnread(50)).thenReturn(List.of(summary));
        when(mailpit.getMessage("m1")).thenReturn(new MailpitMessage(
                "m1", "<m1@example.com>", null, null, "Hi", null, "Hello", null));
    }

    @Test
    void messageStaysUnreadWhileHandledThenIsMarkedRead() {
        when(handler.handle(any())).thenReturn(true);

        monitor.poll();

        InOrder order = inOrder(mailpit, handler);
        order.verify(mailpit).setRead("m1", false);
        order.verify(handler).handle(any());
        order.verify(mailpit).setRead("m1", true);
    }

    @Test
    void crashWhileHandlingLeavesMessageUnread() {
        when(handler.handle(any())).thenThrow(new Error("process died"));

        try {
            monitor.poll();
        } catch (Error expected) {
            // An Error stands in for a crash: nothing after the handler call runs.
        }

        verify(mailpit).setRead("m1", false);
        verify(mailpit, never()).setRead("m1", true);
    }

    @Test
    void failedMessageIsRetriedThenGivenUpOnAfterThreeAttempts() {
        when(handler.handle(any())).thenReturn(false);

        monitor.poll();
        monitor.poll();
        verify(mailpit, never()).setRead("m1", true);

        monitor.poll();
        verify(mailpit).setRead("m1", true);
        verify(handler, times(3)).handle(any());
    }

    @Test
    void handlerExceptionCountsAsFailedAttempt() {
        when(handler.handle(any())).thenThrow(new IllegalStateException("LLM down"));

        monitor.poll();
        monitor.poll();
        monitor.poll();

        verify(mailpit).setRead("m1", true);
    }

    @Test
    void successClearsEarlierFailures() {
        when(handler.handle(any())).thenReturn(false, false, true, false, false);

        monitor.poll();
        monitor.poll();
        monitor.poll();
        monitor.poll();
        monitor.poll();

        // Marked read once for the success; the two later failures start a new count.
        verify(mailpit, times(1)).setRead("m1", true);
    }

    @Test
    void failureToUpdateReadStateDoesNotStopPolling() {
        when(handler.handle(any())).thenReturn(false);
        doThrow(new IllegalStateException("Mailpit down")).when(mailpit).setRead("m1", false);

        monitor.poll();

        verify(handler, never()).handle(any());
    }
}
