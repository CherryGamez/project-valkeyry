# syntax=docker/dockerfile:1.7
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy reactor + dependency manifests first to maximise layer cache.
COPY pom.xml ./
COPY valkeyry-config-plugin/pom.xml valkeyry-config-plugin/
COPY valkeyry-config-plugin/plugin-core/pom.xml valkeyry-config-plugin/plugin-core/
COPY valkeyry-config-plugin/maven-plugin/pom.xml valkeyry-config-plugin/maven-plugin/
COPY valkeyry-config-plugin/gradle-plugin/pom.xml valkeyry-config-plugin/gradle-plugin/
COPY valkeyry-ipaas/pom.xml valkeyry-ipaas/
COPY valkeyry-config/pom.xml valkeyry-config/

RUN mvn -B -ntp -pl valkeyry-config-plugin/plugin-core -am dependency:go-offline || true
RUN mvn -B -ntp -pl valkeyry-config -am dependency:go-offline -DskipTests || true

COPY valkeyry-config-plugin valkeyry-config-plugin
COPY valkeyry-config valkeyry-config

RUN mvn -B -ntp -pl valkeyry-config-plugin/plugin-core -am install -DskipTests
RUN mvn -B -ntp -pl valkeyry-config -am package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S valkeyry && adduser -S valkeyry -G valkeyry
USER valkeyry:valkeyry
WORKDIR /app
COPY --from=build /workspace/valkeyry-config/target/valkeyry-config-*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
