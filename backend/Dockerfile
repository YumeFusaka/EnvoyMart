# 通用镜像构建 —— 一份 Dockerfile 构建所有服务，靠 --build-arg SERVICE=xxx 切换。
#
# 为什么做多阶段：构建需要 Maven + JDK + 整个源码树（约 1GB），
# 而运行只需要一个 JRE 和那个 jar。分成两阶段，最终镜像里没有 Maven、没有源码。
#
# 构建（在 backend/ 目录下执行）：
#   docker build --build-arg SERVICE=auth-service -t envoymart/auth-service:dev .

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

ARG SERVICE=auth-service

# 关键：先只拷 pom 文件，把「下载依赖」单独做成一层。
# 这样只要 pom 没变，改业务代码时这一层直接命中缓存，不用重新下载全部依赖。
COPY pom.xml .
COPY common/pom.xml common/
COPY agent-core/pom.xml agent-core/
COPY auth-service/pom.xml auth-service/
COPY product-service/pom.xml product-service/
COPY order-service/pom.xml order-service/
COPY payment-service/pom.xml payment-service/
COPY review-service/pom.xml review-service/
COPY ai-service/pom.xml ai-service/
COPY gateway-service/pom.xml gateway-service/

RUN mvn -B -q dependency:go-offline -pl ${SERVICE} -am -DskipTests || true

# 再拷全部源码并构建。-am = also make，把依赖的内部模块（如 common）一起构建
COPY . .
RUN mvn -B clean package -pl ${SERVICE} -am -DskipTests

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG SERVICE=auth-service
COPY --from=builder /build/${SERVICE}/target/*.jar app.jar

# 容器里 JVM 默认按「宿主机内存」算堆大小，不显式限制就会吃满。
# 这是把 Spring Boot 塞进小内存节点的必做项。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=55 -XX:+UseSerialGC -Duser.timezone=Asia/Shanghai"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
