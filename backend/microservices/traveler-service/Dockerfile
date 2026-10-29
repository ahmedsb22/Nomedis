# syntax=docker/dockerfile:1.7
# ============================================================================
#  Nomadix - Traveler Service (port 8081 en interne)
# ============================================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src
# Cache Maven partage entre les services : un artefact n'est telecharge qu'une fois.
RUN --mount=type=cache,target=/root/.m2     mvn -B -q clean package -Dmaven.test.skip=true     -Dmaven.wagon.http.retryHandler.count=5

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S nomadix && adduser -S nomadix -G nomadix
COPY --from=build /build/target/*.jar app.jar
USER nomadix
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:--Xms32m -Xmx128m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC} -jar /app/app.jar"]
