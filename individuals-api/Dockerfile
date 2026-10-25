# Образ собирается из заранее собранного jar: ./gradlew bootJar
# (person-client при этом берётся из Nexus; внутри образа Nexus не нужен)
FROM eclipse-temurin:25-jre

RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY build/libs/individuals-api.jar app.jar
USER app

EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
