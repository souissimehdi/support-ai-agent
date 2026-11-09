# Spring AI – Email Support Agent

A fully autonomous AI agent that monitors a support inbox,
reasons with an LLM, acts through MCP tools and replies to customers by email.

## Architecture

```
 customer email ──► Mailpit (SMTP 1025 / REST 8025)
                         ▲            │ poll unread
                 reply   │            ▼
                    ┌────┴─────────────────────┐   MCP (streamable HTTP)   ┌──────────────────┐
                    │ support-agent  :8080      │ ────────────────────────► │ mcp-server :8090 │
                    │ InboxMonitor → SupportAgent│                          │ Query / Action    │
                    │ (ChatClient + OpenAI)      │ ◄──────────────────────── │ tools → MySQL     │
                    └───────────────────────────┘                           └──────────────────┘
```

| Module | Role |
|---|---|
| `mcp-server` | Spring Boot MCP server exposing customer / order / payment / ticket tools backed by MySQL |
| `support-agent` | MCP client agent: polls Mailpit, asks the LLM what to do, calls tools, sends the reply |

## Run

1. `cd mcp-server && ./mvnw spring-boot:run` (Docker Compose starts MySQL with schema + seed data)
2. `export OPENAI_API_KEY=...` then `cd support-agent && ./mvnw spring-boot:run` (starts Mailpit)
3. Import `support-agent-demo.postman_collection.json` and send a request to `POST /seed-mail`
4. Watch the conversation at http://localhost:8025
