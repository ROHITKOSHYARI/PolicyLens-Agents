FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app && mkdir -p /data/uploads && chown -R app:app /data
WORKDIR /app
COPY --from=build /workspace/target/gateway-0.1.0-SNAPSHOT.jar /app/gateway.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
