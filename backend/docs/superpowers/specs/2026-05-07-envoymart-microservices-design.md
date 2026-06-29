# EnvoyMart Microservices Design

## Summary

This phase turns EnvoyMart into a degraded-mode microservices platform that can run locally without mandatory infrastructure while still supporting Nacos, Gateway, Redis, RabbitMQ, Elasticsearch, and Spring AI integrations when available. The backend is split into `gateway-service`, `auth-service`, `product-service`, `order-service`, `ai-service`, and `common` modules. The frontend talks only to the gateway.

## Goals

- Preserve the existing business scope: login, product browsing, cart, checkout, order query, logistics, AI Q&A, recommendations, and tool calling.
- Split backend responsibilities into independently runnable Spring Boot services.
- Keep local startup possible without Nacos, Redis, RabbitMQ, Elasticsearch, or a live LLM.
- Retain a clean migration path to real infrastructure by making every external integration configurable.

## Architecture

### Gateway

Gateway runs on port 8080 and handles JWT validation, route forwarding, and coarse auth gating. It forwards user identity headers downstream so services can stay stateless.

### Auth Service

Auth handles user login and token issuance. It owns the user repository and exposes only auth-related APIs.

### Product Service

Product handles product catalog, product detail, recommendation candidates, and stock mutation endpoints. It supports keyword search and optional Elasticsearch-backed search later.

### Order Service

Order handles cart, checkout, order history, and logistics timeline generation. It calls Product Service for product detail and stock reduction.

### AI Service

AI handles assistant chat, retrieval-augmented answers, tool calling, and recommendation cards. It calls Product Service and Order Service for live data and can swap between a mock provider and a real Spring AI provider.

### Common Module

Common contains cross-service DTOs, result wrappers, JWT utilities, context helpers, and shared constants.

## Data Strategy

Each service keeps its own H2-backed local dataset by default, with MySQL connection properties available through environment variables. This makes the platform runnable without external databases while still looking like a real microservices deployment. Redis, RabbitMQ, and Elasticsearch are optional adapters rather than startup blockers.

## Failure Modes

- If Nacos is unavailable, services continue to run with local static URLs and disabled discovery.
- If Redis or RabbitMQ is unavailable, the order flow falls back to in-process storage and synchronous execution.
- If Elasticsearch or the real LLM is unavailable, search and assistant responses fall back to local keyword retrieval and mock generation.
- If a downstream service is unreachable, the caller returns a clear error through the shared `Result` wrapper.

## Testing

- Gateway route and JWT filter tests.
- Auth login and token tests.
- Product list/detail/search and stock tests.
- Order checkout, cart, and logistics tests.
- AI retrieval, tool-calling, and recommendation tests.
- Frontend build and smoke test against the gateway.
