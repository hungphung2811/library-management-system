# Build context là thư mục gốc monorepo:
#   docker build -f services/migration-service/Dockerfile -t migration-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -B -pl services/migration-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/migration-service/target/migration-service-*-exec.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
