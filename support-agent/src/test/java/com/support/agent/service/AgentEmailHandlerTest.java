package com.support.agent.service;

import com.support.agent.model.AgentResponse;
import com.support.agent.model.IncomingEmail;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEmailHandlerTest {

    private final SupportAgent agent = mock(SupportAgent.class);
    private final SupportMailSender mailSender = mock(SupportMailSender.class);
    private final AgentEmailHandler handler = new AgentEmailHandler(agent, mailSender);
    private final AgentResponse answer = new AgentResponse("Re: Hi", "Hello", "summary");

    @Test
    void failedSendIsRetriedWithoutRunningTheAgentAgain() throws Exception {
        IncomingEmail email = email("priya@example.com");
        when(agent.resolve(email)).thenReturn(answer);
        when(mailSender.sendReply(email, answer)).thenThrow(new IllegalStateException("smtp down")).thenReturn(true);

        assertFalse(handler.handle(email));
        assertTrue(handler.handle(email));

        verify(agent, times(1)).resolve(email);
    }

    @Test
    void emailAlreadyRepliedToIsNotAnsweredTwice() throws Exception {
        IncomingEmail email = email("priya@example.com");
        when(agent.resolve(email)).thenReturn(answer);
        when(mailSender.sendReply(email, answer)).thenReturn(true);

        assertTrue(handler.handle(email));
        assertTrue(handler.handle(email));

        verify(mailSender, times(1)).sendReply(email, answer);
    }

    @Test
    void emailWithNoSenderNeverReachesTheAgent() {
        assertTrue(handler.handle(email("(unknown)")));

        verify(agent, never()).resolve(any());
    }

    @Test
    void customerArgumentsAreOverwrittenWithTheRealSender() {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("issue_refund")
                .description("refund")
                .inputSchema("{\"properties\":{\"customerEmail\":{},\"orderNumber\":{}}}")
                .build());
        when(tool.call(any(String.class))).thenAnswer(call -> call.getArgument(0));

        String sent = SupportAgent.asSender(tool, "mallory@example.com")
                .call("{\"customerEmail\":\"priya@example.com\",\"orderNumber\":\"4471\"}");

        assertEquals("{\"customerEmail\":\"mallory@example.com\",\"orderNumber\":\"4471\"}", sent);
    }

    private static IncomingEmail email(String from) {
        return new IncomingEmail("m1", "<m1@example.com>", from, List.of("support@example.com"), "Hi", "Hello", Instant.now());
    }
}
