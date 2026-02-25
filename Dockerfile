# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先拷依赖描述文件，最大化缓存命中
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw

# ✅ Maven 依赖缓存：只要 pom.xml 不变，这层永远不重新下载
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests dependency:go-offline

# 再拷源码（改代码只会重跑下面这层）
COPY src src

# ✅ 编译也复用同一个 ~/.m2 缓存
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests package


FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /build/target/*jar /app/app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]