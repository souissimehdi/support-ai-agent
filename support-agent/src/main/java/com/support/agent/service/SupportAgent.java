package com.support.agent.service;

import com.support.agent.config.InboxProperties;
import com.support.agent.model.AgentResponse;
import com.support.agent.model.IncomingEmail;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The brain of the agent. Wraps a Spring AI {@link ChatClient} that is wired with
 * the support tools exposed by the MCP server, and lets the LLM drive the whole
 * resolution autonomously: it reads the email, calls whatever MCP tools it needs
 * to identify the customer and gather facts, decides on an action, takes it, and
 * logs a ticket.
 * <p>
 * We never orchestrate the individual tool calls here — the {@code ToolCallbackProvider}
 * supplied by the {@code spring-ai-starter-mcp-client} auto-configuration is handed to
 * the {@code ChatClient}, and Spring AI runs the tool-calling loop for us. Our job is
 * just to give the model its instructions (the system prompt) and the email to act on.
 */
@Service
public class SupportAgent {

    /** Tool arguments that say which customer a call is for. */
    private static final List<String> SENDER_ARGS = List.of("email", "customerEmail");

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpTools;

    public SupportAgent(ChatClient.Builder chatClientBuilder,
                        ToolCallbackProvider mcpTools,
                        InboxProperties inbox,
                        @Value("classpath:/prompts/support-agent-system.st") Resource systemPrompt) {
        this.chatClient = chatClientBuilder
                // Give the model the verbatim instructions for how to work the mailbox,
                // injecting the support address it is acting on behalf of.
                .defaultSystem(sys -> sys.text(systemPrompt)
                        .param("support_address", inbox.address()))
                .build();
        this.mcpTools = mcpTools;
    }

    /**
     * Hand a single email to the LLM and let it resolve the case end to end.
     *
     * @return the agent's structured outcome: the reply to send to the customer
     *         plus an internal summary of what it understood and did.
     */
    public AgentResponse resolve(IncomingEmail email) {
        return chatClient.prompt()
                .user(u -> u.text("""
                        A new email just arrived in the support inbox. Resolve it.

                        From       : {from}
                        To         : {to}
                        Received   : {receivedAt}
                        Subject    : {subject}

                        Body:
                        {body}
                        """)
                        .param("from", email.from())
                        .param("to", String.join(", ", email.to()))
                        .param("receivedAt", email.receivedAt())
                        .param("subject", email.subject())
                        .param("body", email.body()))
                // Expose every tool the MCP server publishes. Spring AI auto-executes
                // these in a loop, so the LLM can take as many steps as it needs.
                .toolCallbacks(Arrays.stream(mcpTools.getToolCallbacks())
                        .map(tool -> asSender(tool, email.from()))
                        .toList())
                .call()
                .entity(AgentResponse.class);
    }

    /**
     * The model picks tool arguments, and an email can talk it into picking someone
     * else's address. So the code, not the model, says who the customer is: every
     * "which customer" argument is overwritten with the real sender.
     */
    static ToolCallback asSender(ToolCallback tool, String sender) {
        String schema = tool.getToolDefinition().inputSchema();
        List<String> senderArgs = SENDER_ARGS.stream().filter(arg -> schema.contains('"' + arg + '"')).toList();
        if (senderArgs.isEmpty()) {
            return tool;
        }
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return tool.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return tool.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                return tool.call(withSender(toolInput));
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return tool.call(withSender(toolInput), toolContext);
            }

            private String withSender(String toolInput) {
                Map<String, Object> args = JsonParser.fromJson(toolInput, new TypeReference<Map<String, Object>>() { });
                senderArgs.forEach(arg -> args.put(arg, sender));
                return JsonParser.toJson(args);
            }
        };
    }
}
