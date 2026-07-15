# EnvoyMart 快速启动

## 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20+ / pnpm
- Docker (可选，用于基础设施)

## 启动基础设施（Docker）

```bash
docker compose up -d nacos redis rabbitmq elasticsearch mysql sentinel-dashboard
```

## 启动微服务

```bash
# 构建后端
cd backend
mvn clean package -DskipTests -pl services/common -am
mvn clean package -DskipTests -pl services/auth-service -am
mvn clean package -DskipTests -pl services/product-service -am
mvn clean package -DskipTests -pl services/order-service -am
mvn clean package -DskipTests -pl services/ai-service -am
mvn clean package -DskipTests -pl services/payment-service -am
mvn clean package -DskipTests -pl services/review-service -am
mvn clean package -DskipTests -pl services/gateway-service -am

# 或逐个启动服务
cd services/auth-service && mvn spring-boot:run
cd services/product-service && mvn spring-boot:run
cd services/order-service && mvn spring-boot:run
# ...
```

## 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

## 访问地址

| 组件 | 地址 |
|------|------|
| 前端页面 | http://localhost:5173 |
| API 网关 | http://localhost:8080 |
| Nacos 控制台 | http://localhost:8848 |
| RabbitMQ 管理 | http://localhost:15672 (envoymart/envoymart123) |
| Sentinel 控制台 | http://localhost:8718 |
| Knife4j 文档 | http://localhost:8080/doc.html |
