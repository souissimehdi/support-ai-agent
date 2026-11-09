package com.support.agent.service;

import com.support.agent.client.MailpitClient;
import com.support.agent.client.dto.MailpitAddress;
import com.support.agent.client.dto.MailpitMessage;
import com.support.agent.client.dto.MailpitMessageSummary;
import com.support.agent.config.InboxProperties;
import com.support.agent.model.IncomingEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls the Mailpit inbox on a fixed interval, turns every unread message into
 * an {@link IncomingEmail}, and forwards it to the configured
 * {@link EmailHandler}.
 * <p>
 * Read/unread state lives on the Mailpit server. Fetching a message marks it
 * read, so it is set back to unread until it has been handled; a crash midway
 * therefore cannot lose it. A failed message is retried on later polls, up to
 * {@value #MAX_ATTEMPTS} attempts, because every attempt costs an LLM run.
 */
@Service
public class InboxMonitor {

    private static final Logger log = LoggerFactory.getLogger(InboxMonitor.class);
    private static final int MAX_ATTEMPTS = 3;

    private final MailpitClient mailpit;
    private final EmailHandler handler;
    private final InboxProperties props;
    // shortcut: counts reset on restart and need one instance, move to a database if the agent ever scales out
    private final Map<String, Integer> failedAttempts = new HashMap<>();

    public InboxMonitor(MailpitClient mailpit, EmailHandler handler, InboxProperties props) {
        this.mailpit = mailpit;
        this.handler = handler;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${support-agent.inbox.poll-interval:10000}")
    public void poll() {
        try {
            List<MailpitMessageSummary> unread = mailpit.listUnread(props.batchSize());
            if (unread.isEmpty()) {
                log.debug("No new mail");
                return;
            }
            log.info("Found {} new message(s)", unread.size());
            for (MailpitMessageSummary summary : unread) {
                processOne(summary.id());
            }
        } catch (Exception e) {
            // Mailpit may be starting up or briefly unreachable. Log and let the
            // next scheduled poll retry rather than killing the scheduler.
            log.warn("Inbox poll failed: {}", e.getMessage(), e);
        }
    }

    private void processOne(String id) {
        try {
            // Fetching the full message marks it read on the server.
            MailpitMessage message = mailpit.getMessage(id);
            mailpit.setRead(id, false);
            if (handler.handle(toIncomingEmail(message))) {
                mailpit.setRead(id, true);
                failedAttempts.remove(id);
                return;
            }
            log.warn("Handler could not finish message {}", id);
        } catch (Exception e) {
            log.error("Failed to process message {}", id, e);
        }
        recordFailure(id);
    }

    private void recordFailure(String id) {
        int attempts = failedAttempts.merge(id, 1, Integer::sum);
        boolean giveUp = attempts >= MAX_ATTEMPTS;
        if (giveUp) {
            failedAttempts.remove(id);
            log.error("Giving up on message {} after {} attempts; it needs a person to handle it", id, attempts);
        }
        try {
            // Unread means the next poll retries it; read means it is left alone.
            mailpit.setRead(id, giveUp);
        } catch (Exception e) {
            log.warn("Could not update read state of message {}: {}", id, e.getMessage());
        }
    }

    private IncomingEmail toIncomingEmail(MailpitMessage message) {
        String from = message.from() != null ? message.from().address() : "(unknown)";
        List<String> to = message.to().stream()
                .map(MailpitAddress::address)
                .toList();
        String subject = message.subject() != null ? message.subject() : "";
        String body = bestBody(message);
        Instant receivedAt = parseDate(message.date());
        return new IncomingEmail(message.messageId(), from, to, subject, body, receivedAt);
    }

    /** Prefer the plain-text body; fall back to HTML if that is all there is. */
    private String bestBody(MailpitMessage message) {
        if (message.text() != null && !message.text().isBlank()) {
            return message.text().strip();
        }
        return message.html() != null ? message.html().strip() : "";
    }

    private Instant parseDate(String date) {
        if (date == null || date.isBlank()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(date).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(date);
            } catch (Exception ex) {
                return Instant.now();
            }
        }
    }
}
