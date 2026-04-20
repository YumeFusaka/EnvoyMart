# EnvoyMart Microservices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a degraded-mode EnvoyMart microservices platform with a gateway, auth, product, order, ai, and common module, plus a frontend that talks only to the gateway.

**Architecture:** Create a separate Maven-based microservices workspace under `backend/services`. Each service is an independently runnable Spring Boot app with local H2 defaults and configurable integration points for Nacos, MySQL, Redis, RabbitMQ, Elasticsearch, and Spring AI. Gateway performs JWT validation and routes traffic to the backend services; AI and order services call other services over HTTP.

**Tech Stack:** Spring Boot 3.5, Spring Cloud 2025, Spring Cloud Alibaba 2025, Spring Cloud Gateway, OpenFeign, MyBatis-Plus, Spring AI 1.0, H2, MySQL, Vue 3, TypeScript

---

### Task 1: Workspace and common module

**Files:**
- Create: `backend/services/pom.xml`
- Create: `backend/services/common/pom.xml`
- Create: `backend/services/common/src/main/java/yumefusaka/envoymart/common/...`
- Create: `backend/services/common/src/test/java/yumefusaka/envoymart/common/...`

- [ ] Add the parent Maven workspace and module list.
- [ ] Add shared result, JWT, context, and exception utilities.
- [ ] Add tests for JWT create/parse and result serialization.

### Task 2: Gateway service

**Files:**
- Create: `backend/services/gateway-service/pom.xml`
- Create: `backend/services/gateway-service/src/main/java/yumefusaka/envoymart/gateway/...`
- Create: `backend/services/gateway-service/src/main/resources/application.yml`
- Create: `backend/services/gateway-service/src/test/java/yumefusaka/envoymart/gateway/...`

- [ ] Add Gateway routes to auth, product, order, and ai services.
- [ ] Add JWT validation filter and user header forwarding.
- [ ] Add tests for public login routing and protected route rejection.

### Task 3: Auth service

**Files:**
- Create: `backend/services/auth-service/pom.xml`
- Create: `backend/services/auth-service/src/main/java/yumefusaka/envoymart/authservice/...`
- Create: `backend/services/auth-service/src/main/resources/schema.sql`
- Create: `backend/services/auth-service/src/main/resources/data.sql`
- Create: `backend/services/auth-service/src/test/java/yumefusaka/envoymart/authservice/...`

- [ ] Add user entity, repository, login controller, and token issuance.
- [ ] Add local H2 seed users and environment-based MySQL support.
- [ ] Add login tests for success and failure.

### Task 4: Product service

**Files:**
- Create: `backend/services/product-service/pom.xml`
- Create: `backend/services/product-service/src/main/java/yumefusaka/envoymart/productservice/...`
- Create: `backend/services/product-service/src/main/resources/schema.sql`
- Create: `backend/services/product-service/src/main/resources/data.sql`
- Create: `backend/services/product-service/src/test/java/yumefusaka/envoymart/productservice/...`

- [ ] Add product catalog, detail, recommendation, and stock mutation APIs.
- [ ] Add local search fallback and optional Elasticsearch adapter.
- [ ] Add tests for filtering, detail, and stock decrement.

### Task 5: Order service

**Files:**
- Create: `backend/services/order-service/pom.xml`
- Create: `backend/services/order-service/src/main/java/yumefusaka/envoymart/orderservice/...`
- Create: `backend/services/order-service/src/main/resources/schema.sql`
- Create: `backend/services/order-service/src/main/resources/data.sql`
- Create: `backend/services/order-service/src/test/java/yumefusaka/envoymart/orderservice/...`

- [ ] Add cart, checkout, order history, order detail, and logistics APIs.
- [ ] Call Product Service to read product data and reduce stock.
- [ ] Add tests for cart CRUD, checkout, and logistics response.

### Task 6: AI service

**Files:**
- Create: `backend/services/ai-service/pom.xml`
- Create: `backend/services/ai-service/src/main/java/yumefusaka/envoymart/aiservice/...`
- Create: `backend/services/ai-service/src/main/resources/application.yml`
- Create: `backend/services/ai-service/src/test/java/yumefusaka/envoymart/aiservice/...`

- [ ] Add retrieval documents, tool calling, and session memory.
- [ ] Call Product and Order services for live recommendation and logistics data.
- [ ] Add a Spring AI provider abstraction with mock default and optional real model.
- [ ] Add tests for product advice, order lookup, logistics, and recommendations.

### Task 7: Frontend gateway integration

**Files:**
- Modify: `frontend/src/utils/axios.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/LoginView.vue`
- Modify: `frontend/src/views/ShopView.vue`
- Modify: `frontend/src/views/AiAssistantView.vue`

- [ ] Retarget all frontend calls to the gateway.
- [ ] Verify login, shop, checkout, and AI flows against the new service URLs.
- [ ] Run frontend type-check and build.

### Task 8: Cross-service verification

**Files:**
- Modify: service configs and tests as needed

- [ ] Run each service test suite.
- [ ] Run gateway + auth + product + order + ai smoke flow.
- [ ] Fix any routing, serialization, or startup issues discovered in smoke testing.
