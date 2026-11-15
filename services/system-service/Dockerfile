# Build context là thư mục gốc monorepo:
#   docker build -f services/system-service/Dockerfile -t system-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -B -pl services/system-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/system-service/target/system-service-*-exec.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
