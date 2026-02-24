# =========================================
# Stage 1 — deps: 依赖缓存层（加速）
# =========================================
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw
RUN ./mvnw -B -q -DskipTests dependency:go-offline

# =========================================
# Stage 2 — builder: 构建层
# =========================================
FROM deps AS builder
WORKDIR /build

COPY src src
RUN ./mvnw -B -DskipTests clean package

# =========================================
# Stage 3 — runtime: Distroless 运行层
# =========================================
#FROM gcr.io/distroless/java21-debian12:nonroot
FROM eclipse-temurin:21-jre
WORKDIR /app

# 复制 jar（如果 target 里会产出多个 jar，建议改成精确文件名）
#COPY --from=builder /build/target/*.jar /app/app.jar
COPY --from=builder /build/target/agent-0.0.1-SNAPSHOT.jar /app/app.jar

# 生产环境建议增加这些 JVM 参数（按你 2c4g 适当调）
# distroless 没有 shell，所以用 JAVA_TOOL_OPTIONS 注入最方便
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# 注意：distroless 的入口点就是 java，所以直接传参
# profile 建议用环境变量 SPRING_PROFILES_ACTIVE（compose 里配），这里也可写死
ENTRYPOINT ["/usr/bin/java","-jar","/app/app.jar"]