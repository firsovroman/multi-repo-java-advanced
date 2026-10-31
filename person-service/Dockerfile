# Образ собирается из заранее собранного jar: ./gradlew bootJar
FROM eclipse-temurin:25-jre

RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY build/libs/person-service.jar app.jar
USER app

EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
