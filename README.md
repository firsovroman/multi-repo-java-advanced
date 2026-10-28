# multi-repo-java-advanced

Payment Platform — модуль 1: individuals-api.

Внешний входной слой платформы: регистрация, вход, обновление токена и получение текущего пользователя.
`individuals-api` оркестрирует **person-service** (доменный пользователь, `user_uid`) и **Keycloak**
(учётная запись и токены) и сам доменных данных не хранит. Контекст и договорённости — в [CONTEXT.md](CONTEXT.md).

## Структура монорепозитория

Корень — не Gradle-проект. У каждого модуля свой Gradle Wrapper; модули зависят друг от друга
только через опубликованные артефакты (`com.example:person-client`).

| Путь | Назначение |
|---|---|
| `gradle/libs.versions.toml` | общий каталог версий, подключается каждым модулем |
| `person-service/openapi/person-service.yaml` | финальный контракт сервиса пользователей |
| `person-service/src/main/resources/db/migration` | миграции Flyway для модуля 2 |
| `person-client/` | HTTP Service Client (`@HttpExchange`) и DTO, сгенерированные из `person-service.yaml` |
| `individuals-api/` | оркестратор аутентификации; контракт — `individuals-api/openapi/individuals-api.yaml` |
| `individuals-api/src/main/resources/realm/realm-export.json` | realm Keycloak `payment-platform` |
| `infra/` | Prometheus, Tempo, Grafana (provisioning + дашборд), заглушка person-service на WireMock |
| `postman/individuals-api.postman_collection.json` | ручная проверка сценариев |
| `insomnia/individuals-api.insomnia.yaml` | то же для Insomnia (Import → файл) |
| `insomnia/person-service.insomnia.yaml` | проверка контракта person-service в Insomnia (на стенде — заглушка WireMock, порт 8082) |
| `docker-compose.yml` | локальный стенд |

## Требования

- JDK 25 — Gradle скачает его сам (toolchain + foojay), для запуска Gradle достаточно JDK 17+;
- Docker Engine + Docker Compose v2 (на Windows — Docker Desktop с WSL2);
- свободные порты: 3000, 3200, 4318, 5433, 5434, 8080, 8081, 8082, 9090.

## Быстрый старт

```bash
# 1. Опубликовать person-client в Nexus (см. «Nexus»; без Nexus — ./gradlew publishToMavenLocal)
cd person-client && ./gradlew publish && cd ..

# 2. Собрать individuals-api (Dockerfile копирует готовый jar)
cd individuals-api && ./gradlew bootJar && cd ..

# 3. Поднять стенд
docker compose up --build -d
```

| Сервис | Адрес | Доступ |
|---|---|---|
| individuals-api | http://localhost:8081 | — |
| Keycloak | http://localhost:8080 | admin / admin, realm `payment-platform` |
| person-service (заглушка WireMock) | http://localhost:8082 | `taken@example.com` всегда даёт 409 |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | admin / admin, дашборд *Payment Platform / individuals-api* |
| Tempo | http://localhost:3200 | трассы — через Grafana → Explore → Tempo |

Сервис `person-migrations` при старте применяет миграции person-service к `person-postgres` и завершается.

## Примеры вызовов

```bash
# Регистрация -> 201
curl -X POST http://localhost:8081/api/v1/auth/registration \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Str@ngPass!","confirmPassword":"Str@ngPass!","firstName":"Ivan","lastName":"Ivanov"}'
# {"userId":"3d6f3532-...","accessToken":"<jwt>","refreshToken":"<jwt>","expiresIn":300,"tokenType":"Bearer"}

# Вход -> 200
curl -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Str@ngPass!"}'

# Обновление токена -> 200
curl -X POST http://localhost:8081/api/v1/auth/refresh-token \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<jwt>"}'

# Текущий пользователь -> 200
curl http://localhost:8081/api/v1/auth/me -H "Authorization: Bearer <jwt>"
# {"userUid":"3d6f3532-...","keycloakUserId":"6d4a6249-...","email":"user@example.com",
#  "firstName":"Ivan","lastName":"Ivanov","roles":["USER"],"emailVerified":false}
```

Ошибки возвращаются в едином формате `ErrorResponse`; код бизнес-ошибки — в поле `error`:

```json
{"timestamp":"2026-06-08T10:15:30Z","path":"/api/v1/auth/registration","status":409,
 "error":"CONFLICT","message":"Пользователь с таким email уже существует",
 "traceId":"803b448a0489f84084905d3093480352","details":[]}
```

| Ситуация | HTTP | `error` |
|---|---|---|
| невалидный запрос | 400 | `VALIDATION_ERROR` |
| пароль и подтверждение не совпадают | 400 | `PASSWORD_MISMATCH` |
| неверный email или пароль | 401 | `INVALID_CREDENTIALS` |
| refresh token недействителен | 401 | `INVALID_REFRESH_TOKEN` |
| нет или неверный Bearer token | 401 | `UNAUTHORIZED` |
| email уже существует (person-service) | 409 | `CONFLICT` |
| person создан, Keycloak не завершил регистрацию | 502 | `REGISTRATION_INCONSISTENT` (+ `userUid` в `details`) |
| внешний сервис вернул неожиданную ошибку | 502 | `EXTERNAL_SERVICE_ERROR` |
| внешний сервис недоступен | 503 | `EXTERNAL_SERVICE_UNAVAILABLE` |

Проверить частичный сбой: `docker compose stop keycloak`, выполнить регистрацию, затем `docker compose start keycloak`.
В журнале появится ERROR-запись с `"event":"INCONSISTENT_REGISTRATION"` и `userUid`.

## Тесты

Для интеграционных тестов нужен запущенный Docker (Testcontainers поднимает Keycloak и PostgreSQL).

```bash
cd person-service && ./gradlew check    # openApiValidate + IT-DB-001 (Flyway на PostgreSQL)
cd person-client  && ./gradlew openApiValidate build
cd individuals-api && ./gradlew check   # openApiValidate + unit + integration + покрытие ≥ 80 %
```

Отчёт о покрытии: `individuals-api/build/reports/jacoco/test/html/index.html`. Порог 80 % по строкам проверяется
для пакетов `service`, `gateway`, `keycloak`.

| Код | Тест |
|---|---|
| UT-REG-001…004, UT-LOG-001/002, UT-REF-001, UT-ME-001 | `unit/AuthServiceTest`, HTTP-уровень — `unit/AuthControllerTest` |
| перевод ошибок внешних систем | `unit/PersonGatewayTest`, `unit/KeycloakGatewayTest` |
| IT-KC-001…003, refresh, /me | `integration/KeycloakRegistrationIT` |
| IT-OBS-001…003 | `integration/ObservabilityIT` |
| IT-DB-001 | `person-service: FlywayMigrationIT` |

## Наблюдаемость

- **Метрики**: `/actuator/prometheus`. Прикладные метрики: `auth_registration_total`, `auth_registration_success_total`,
  `auth_registration_failure_total`, `auth_login_total`, `auth_login_failure_total`, `auth_refresh_total`; таймеры
  `external_keycloak_requests_seconds` и `external_person_service_requests_seconds` (теги `operation`, `outcome`).
- **Трассы**: OpenTelemetry → OTLP HTTP → Tempo (`http://tempo:4318/v1/traces`); trace context передаётся в
  person-service и Keycloak (заголовок `traceparent`). `traceId` из ответа об ошибке можно искать в Grafana → Explore → Tempo.
- **Журналы**: JSON (ECS) в stdout через встроенное structured logging Spring Boot (`logging.structured.format.console=ecs`),
  поэтому отдельный `logback-spring.xml` не нужен. В записях есть `service.name`, `traceId`, `spanId`, `http.path`,
  `http.method`, `http.status`, `errorCode` и `userUid`, если он уже известен.

## Nexus

person-client публикуется в Nexus (локально — http://localhost:8091, репозиторий `maven-releases`; Nexus запускается
отдельным compose: `docker compose -f nexus/docker-compose.yml up -d`). Репозиторий подключается только конфигурацией, код менять не нужно:

```bash
# ~/.gradle/gradle.properties или переменные окружения NEXUS_URL / NEXUS_USERNAME / NEXUS_PASSWORD
nexusUrl=http://localhost:8091/repository/maven-releases/
nexusUsername=admin
nexusPassword=...

cd person-client && ./gradlew publish
```

Если `nexusUrl` задан, individuals-api берёт person-client из Nexus (`mavenLocal()` остаётся запасным вариантом
для `publishToMavenLocal`). В `maven-releases` повторная публикация той же версии запрещена: после изменения
контракта поднимите версию `person-client` в `gradle/libs.versions.toml`.
