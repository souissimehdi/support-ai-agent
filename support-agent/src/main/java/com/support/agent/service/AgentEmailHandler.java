package com.support.agent.service;

import com.support.agent.model.AgentResponse;
import com.support.agent.model.IncomingEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The {@link EmailHandler}: hands every new email to the {@link SupportAgent}
 * so the LLM, backed by the MCP tools, resolves it autonomously.
 * <p>
 * If anything fails we return {@code false} so the message is left unread and
 * retried on the next poll. A retry only redoes the step that failed: the agent
 * logs a ticket and may refund, so running it twice for one email is not safe.
 */
@Component
public class AgentEmailHandler implements EmailHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentEmailHandler.class);

    private final SupportAgent agent;
    private final SupportMailSender mailSender;
    // shortcut: both are lost on restart and need one instance, move to a database if the agent ever scales out
    /** Answers the agent produced whose reply has not been sent yet, by mailbox message id. */
    private final Map<String, AgentResponse> unsent = new HashMap<>();
    /** Messages already replied to; they only come back if marking them read failed. */
    private final Set<String> replied = new HashSet<>();

    public AgentEmailHandler(SupportAgent agent, SupportMailSender mailSender) {
        this.agent = agent;
        this.mailSender = mailSender;
    }

    @Override
    public boolean handle(IncomingEmail email) {
        if (replied.contains(email.id())) {
            return true;
        }
        if (email.from() == null || email.from().isBlank() || "(unknown)".equals(email.from())) {
            // Nobody to reply to, and no retry can change that.
            log.error("Email \"{}\" has no sender address; it needs a person to handle it", email.subject());
            return true;
        }
        try {
            AgentResponse response = unsent.get(email.id());
            if (response == null) {
                log.info("Handing email from {} (subject: \"{}\") to the support agent", email.from(), email.subject());
                response = agent.resolve(email);
                unsent.put(email.id(), response);
                log.info("""
                        === Agent resolution ===
                        From    : {}
                        Subject : {}
                        Outcome :
                        {}
                        ========================""",
                        email.from(), email.subject(), response.operatorSummary());
            }
            // Send the drafted reply back to the customer, threaded onto their email.
            if (!mailSender.sendReply(email, response)) {
                // The agent wrote no reply; let the next attempt ask it again.
                unsent.remove(email.id());
                return false;
            }
            unsent.remove(email.id());
            replied.add(email.id());
            return true;
        } catch (Exception e) {
            // Anything from a transient LLM/MCP error to a failed send lands here.
            // Leave the mail unread so the next poll retries it.
            log.error("Agent failed to resolve email from {} (subject: \"{}\"); will retry",
                    email.from(), email.subject(), e);
            return false;
        }
    }
}
