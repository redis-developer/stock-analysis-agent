# Stock Analysis Agent

This repository is a stock analysis demo that showcases Spring AI, a multi agent architecture, Redis rate limiting, Redis session management, LangCache semantic caching, regular Redis caching, Redis agent memory for working memory and long term memory, and semantic guardrails with semantic routing through RedisVL.

The application is a Spring Boot web app with a browser chat UI. A coordinator agent receives the user request, decides which specialist agents to call, and returns a synthesized stock analysis response.

The project uses Java 25, Gradle Kotlin DSL, Spring Boot 4.0.6, and Spring AI 2.0.0 M2.

## What It Demonstrates

1. Spring AI chat clients, advisors, chat memory, and OpenAI chat and embedding models.
2. A multi agent flow with coordinator, market data, fundamentals, news, technical analysis, and synthesis agents.
3. Redis backed rate limiting for chat requests with Bucket4j.
4. Redis backed HTTP session management with Spring Session.
5. Semantic caching through LangCache for similar stock analysis requests.
6. Regular Redis caching for external market, technical, SEC, and news data.
7. Redis agent memory for recent working memory and long term memories.
8. Semantic guardrails with RedisVL semantic routing before agent execution.

Redis 8 is enough for the local Redis features used by this demo. Redis Stack is not required.

## Main Components

The chat entry point is `src/main/java/com/redis/stockanalysisagent/chat/ChatController.java`.

The main orchestration path is `src/main/java/com/redis/stockanalysisagent/chat/ChatAnalysisService.java` and `src/main/java/com/redis/stockanalysisagent/agent/coordinator/CoordinatorAgent.java`.

Specialist agents live under `src/main/java/com/redis/stockanalysisagent/agent`.

Redis caching code lives under `src/main/java/com/redis/stockanalysisagent/cache` and `src/main/java/com/redis/stockanalysisagent/semanticcache`.

Session management code lives under `src/main/java/com/redis/stockanalysisagent/session` and `src/main/java/com/redis/stockanalysisagent/sessionmanagement`.

Rate limiting code lives under `src/main/java/com/redis/stockanalysisagent/ratelimiting`.

Agent memory code lives under `src/main/java/com/redis/stockanalysisagent/memory`.

Semantic guardrail code lives under `src/main/java/com/redis/stockanalysisagent/semanticguardrail`.

## Requirements

1. Java 25.
2. Redis 8 on localhost port 6379, or Redis connection settings in environment variables.
3. An OpenAI API key for Spring AI chat and embeddings.
4. LangCache settings when semantic caching is enabled.
5. Redis agent memory settings when memory features are enabled.
6. Optional Twelve Data, Tavily, and SEC settings for external stock data sources.

## Configuration

The main configuration file is `src/main/resources/application.yaml`.

Set these environment variables before running the app:

```bash
export STOCK_ANALYSIS_AGENT_OPENAI_API_KEY=your_openai_api_key
export STOCK_ANALYSIS_AGENT_REDIS_HOST=localhost
export STOCK_ANALYSIS_AGENT_REDIS_PORT=6379
export STOCK_ANALYSIS_AGENT_LANGCACHE_ENDPOINT=your_langcache_url
export STOCK_ANALYSIS_AGENT_LANGCACHE_CACHE_ID=your_langcache_cache_id
export STOCK_ANALYSIS_AGENT_LANGCACHE_API_KEY=your_langcache_api_key
export STOCK_ANALYSIS_AGENT_AGENT_MEMORY_ENDPOINT=your_agent_memory_endpoint
export STOCK_ANALYSIS_AGENT_AGENT_MEMORY_STORE_ID=your_agent_memory_store_id
export STOCK_ANALYSIS_AGENT_AGENT_MEMORY_API_KEY=your_agent_memory_api_key
```

Provider keys:

```bash
export STOCK_ANALYSIS_AGENT_TWELVE_DATA_API_KEY=your_twelve_data_api_key
export STOCK_ANALYSIS_AGENT_TAVILY_API_KEY=your_tavily_api_key
export SEC_USER_AGENT="stock analysis agent you@example.com"
```

## Run

Start Redis 8 locally.

Run the app:

```bash
./gradlew bootRun
```

Open the chat UI:

```text
http://localhost:8080
```

## Test

```bash
./gradlew test
```

## Docker

Build the container:

```bash
docker build -t stock-analysis-agent .
```

Run it with the same environment variables listed above:

```bash
docker run --rm -p 8080:8080 --env-file .env stock-analysis-agent
```

## Docs

More focused implementation notes are in these files:

1. `docs/rate_limiter.md`
2. `docs/session_management.md`
3. `docs/caching.md`
