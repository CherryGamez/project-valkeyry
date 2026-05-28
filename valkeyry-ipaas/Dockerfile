# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY valkeyry-ipaas/pom.xml valkeyry-ipaas/
RUN mvn -B -ntp -pl valkeyry-ipaas -am dependency:go-offline -DskipTests || true
COPY valkeyry-ipaas valkeyry-ipaas
RUN mvn -B -ntp -pl valkeyry-ipaas -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S valkeyry && adduser -S valkeyry -G valkeyry
USER valkeyry:valkeyry
WORKDIR /app
COPY --from=build /workspace/valkeyry-ipaas/target/valkeyry-ipaas-*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8082/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
