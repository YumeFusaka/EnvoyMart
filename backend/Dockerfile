ARG SERVICE_NAME

### 编译阶段
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先复制依赖配置文件，利用缓存
COPY pom.xml ./
COPY common/ ./common/
COPY ${SERVICE_NAME}/ ./${SERVICE_NAME}/

RUN mvn -pl ${SERVICE_NAME} -am package -DskipTests -q

### 运行阶段
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/${SERVICE_NAME}/target/*.jar ./app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
