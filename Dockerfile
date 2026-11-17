FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY backend/ backend/
COPY frontend/ frontend/

RUN mvn -B -f backend/pom.xml clean package


FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/backend/target/deadlock-playground-1.0.jar app.jar

ENV BIND_ADDRESS=0.0.0.0

EXPOSE 10000

CMD ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "app.jar"]
