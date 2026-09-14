# 构建阶段不读取宿主机 target 或私有 .env。
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY common ./common
COPY order-server ./order-server
COPY admin-server ./admin-server
COPY gateway-server ./gateway-server
COPY integration-tests/pom.xml ./integration-tests/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
# ffprobe 用于实际校验宠物视频，字体用于图片验证码。
RUN apt-get update && apt-get install -y --no-install-recommends curl ffmpeg fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home app
WORKDIR /app
ARG SERVICE
COPY --from=build /src/${SERVICE}/target/*-exec.jar /app/app.jar
RUN mkdir -p /app/data/files && chown -R app:app /app
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -Djava.awt.headless=true"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
