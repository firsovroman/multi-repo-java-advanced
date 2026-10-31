# multi-repo-java-advanced

Payment Platform — модули 1 и 2: individuals-api и person-service.

Внешний входной слой платформы: регистрация, вход, обновление токена и получение текущего пользователя.
`individuals-api` оркестрирует **person-service** (доменный пользователь, `user_uid`) и **Keycloak**
(учётная запись и токены) и сам доменных данных не хранит. `person-service` — транзакционный CRUD агрегата
user + address + individual с аудитом Hibernate Envers. Контекст и договорённости — в [CONTEXT.md](CONTEXT.md).

## Структура монорепозитория

Корень — не Gradle-проект. У каждого модуля свой Gradle Wrapper; модули зависят друг от друга
только через опубликованные артефакты (`com.example:person-client`).

| Путь | Назначение |
|---|---|
| `gradle/libs.versions.toml` | общий каталог версий, подключается каждым модулем |
| `person-service/` | сервис доменных пользователей (Spring MVC, JPA, Envers) |
| `person-service/openapi/person-service.yaml` | контракт: `/persons/*` для individuals-api, `/users/*` — CRUD агрегата |
| `person-service/src/main/resources/db/migration` | миграции Flyway V001–V006, применяются при старте сервиса |
| `person-client/` | HTTP Service Client (`@HttpExchange`) и DTO, сгенерированные из `person-service.yaml` |
| `individuals-api/` | оркестратор аутентификации; контракт — `individuals-api/openapi/individuals-api.yaml` |
| `individuals-api/src/main/resources/realm/realm-export.json` | realm Keycloak `payment-platform` |
| `infra/` | Prometheus, Tempo, Grafana (provisioning + дашборды), заглушка person-service на WireMock |
| `postman/individuals-api.postman_collection.json` | ручная проверка сценариев |
| `postman/person-service.postman_collection.json` | ручная проверка `/persons/*` и `/users/*` |
| `insomnia/individuals-api.insomnia.yaml` | то же для Insomnia (Import → файл) |
| `insomnia/person-service.insomnia.yaml` | то же для person-service (порт 8082) |
| `docker-compose.yml` | локальный стенд |

## Требования

- JDK 25 — Gradle скачает его сам (toolchain + foojay), для запуска Gradle достаточно JDK 17+;
- Docker Engine + Docker Compose v2 (на Windows — Docker Desktop с WSL2);
- свободные порты: 3000, 3200, 4318, 5433, 5434, 8080, 8081, 8082, 9090.

## Быстрый старт

```bash
# 1. Опубликовать person-client в Nexus (см. «Nexus»; без Nexus — ./gradlew publishToMavenLocal)
cd person-client && ./gradlew publish && cd ..

# 2. Собрать сервисы (Dockerfile копирует готовый jar)
cd person-service && ./gradlew bootJar && cd ..
cd individuals-api && ./gradlew bootJar && cd ..

# 3. Поднять стенд
docker compose up --build -d
```

| Сервис | Адрес | Доступ |
|---|---|---|
| individuals-api | http://localhost:8081 | — |
| Keycloak | http://localhost:8080 | admin / admin, realm `payment-platform` |
| person-service | http://localhost:8082 | только для локальной разработки: аутентификации нет, в общем контуре порт должен быть доступен лишь individuals-api |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | admin / admin, дашборды *Payment Platform / individuals-api* и *person-service* |
| Tempo | http://localhost:3200 | трассы — через Grafana → Explore → Tempo |

Миграции применяет сам person-service при старте (Flyway, схема `person`). Заглушка WireMock осталась для запуска без БД:
`docker compose --profile stub up person-service-stub`; её порт 8082 занят настоящим сервисом, поэтому вместе они не запускаются.

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
# {"userId":"3d6f3532-...","keycloakUserId":"6d4a6249-...","email":"user@example.com",
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
| person создан, Keycloak не завершил регистрацию | 502 | `REGISTRATION_INCONSISTENT` (+ `userId` в `details`) |
| внешний сервис вернул неожиданную ошибку | 502 | `EXTERNAL_SERVICE_ERROR` |
| внешний сервис недоступен | 503 | `EXTERNAL_SERVICE_UNAVAILABLE` |

Проверить частичный сбой: `docker compose stop keycloak`, выполнить регистрацию, затем `docker compose start keycloak`.
В журнале появится ERROR-запись с `"event":"INCONSISTENT_REGISTRATION"` и `userUid`.

## person-service

Внутренний сервис, вызывается только из individuals-api. Обе группы операций работают через один `UserService`:

| Операция | Назначение | Успех | Ошибки |
|---|---|---|---|
| `POST /api/v1/persons/registration` | создать пользователя, вернуть `userId` | 201 | 400, 409 |
| `GET /api/v1/persons/{userUid}` | компактный `PersonResponse` | 200 | 400, 404 |
| `GET /api/v1/persons/by-email?email=` | то же по email | 200 | 400, 404 |
| `POST /api/v1/users` | создать агрегат | 201 + `Location`, `ETag` | 400, 409 |
| `GET /api/v1/users/{id}` | агрегат целиком | 200 + `ETag` | 400, 404 |
| `GET /api/v1/users/by-email?email=` | поиск без учёта регистра | 200 + `ETag` | 400, 404 |
| `PATCH /api/v1/users/{id}` | частичное обновление, необязательный `If-Match: "<version>"` | 200 + `ETag` | 400, 404, 409, 412 |
| `DELETE /api/v1/users/{id}` | физическое удаление агрегата | 204 | 400, 404 |

Ошибки — `application/problem+json` (RFC 9457) с расширениями `errorCode`, `traceId` и, для `VALIDATION_ERROR`, `errors`:

```json
{"type":"https://example.org/problems/email-already-exists","title":"Конфликт данных","status":409,
 "detail":"Пользователь с таким email уже существует","instance":"/api/v1/persons/registration",
 "errorCode":"EMAIL_ALREADY_EXISTS","traceId":"803b448a0489f84084905d3093480352"}
```

| HTTP | `errorCode` | Когда |
|---|---|---|
| 400 | `VALIDATION_ERROR` | нарушены ограничения запроса |
| 400 | `MALFORMED_REQUEST` | тело или UUID не разбираются, неверный `If-Match` |
| 400 | `COUNTRY_NOT_FOUND`, `COUNTRY_MISMATCH` | страны нет в справочнике, alpha2 не соответствует alpha3 |
| 404 | `USER_NOT_FOUND` | нет пользователя по id или email |
| 409 | `EMAIL_ALREADY_EXISTS` | email занят без учёта регистра |
| 409 | `CONCURRENT_UPDATE` | оптимистическая блокировка |
| 412 | `VERSION_MISMATCH` | `If-Match` не совпал с версией |
| 500 | `INTERNAL_ERROR` | всё остальное; подробности только в журнале с тем же `traceId` |

Аудит: Hibernate Envers пишет в `person.revinfo` (с `trace_id`) и `person.*_aud`, у `users` и `individuals` есть флаги `*_mod`.
HTTP-метода для чтения аудита нет: доступ через `AuditReader` и SQL.

## Тесты

Для интеграционных тестов нужен запущенный Docker (Testcontainers поднимает Keycloak и PostgreSQL).
IT-PS-013 в individuals-api запускает образ person-service из готового jar: без `person-service/build/libs/person-service.jar` он пропускается.

```bash
cd person-service && ./gradlew check    # openApiValidate + unit + integration + покрытие ≥ 80 %
cd person-client  && ./gradlew openApiValidate build
cd individuals-api && ./gradlew check   # openApiValidate + unit + integration + покрытие ≥ 80 %
```

Отчёт о покрытии: `<модуль>/build/reports/jacoco/test/html/index.html`. Порог 80 % по строкам проверяется
для пакетов `service`, `gateway`, `keycloak` в individuals-api и `service`, `mapper`, `error`, `controller` в person-service.

| Код | Тест |
|---|---|
| UT-REG-001…004, UT-LOG-001/002, UT-REF-001, UT-ME-001 | `unit/AuthServiceTest`, HTTP-уровень — `unit/AuthControllerTest` |
| перевод ошибок внешних систем | `unit/PersonGatewayTest`, `unit/KeycloakGatewayTest` |
| IT-KC-001…003, refresh, /me | `integration/KeycloakRegistrationIT` |
| IT-OBS-001…003 | `integration/ObservabilityIT` |
| IT-DB-001, IT-PS-001 | `person-service: FlywayMigrationIT` |
| UT-PS-001…008 | `person-service: unit/UserServiceTest` |
| UT-PS-009 | `person-service: unit/UserMapperTest` |
| UT-PS-010 и HTTP-уровень | `person-service: unit/ControllerLayerTest` |
| IT-PS-002…005, IT-PS-007 | `person-service: integration/UserCrudIT` |
| IT-PS-006, IT-PS-009 | `person-service: integration/UserTransactionsIT` |
| IT-PS-008 | `person-service: integration/AuditIT` |
| IT-PS-010 | `person-service: integration/ProblemFormatIT` |
| IT-PS-011, IT-PS-012 | `person-service: integration/ObservabilityIT` |
| IT-PS-013 | `individuals-api: integration/PersonServiceCompatibilityIT` |

## Наблюдаемость

- **Метрики**: `/actuator/prometheus`. Прикладные метрики: `auth_registration_total`, `auth_registration_success_total`,
  `auth_registration_failure_total`, `auth_login_total`, `auth_login_failure_total`, `auth_refresh_total`; таймеры
  `external_keycloak_requests_seconds` и `external_person_service_requests_seconds` (теги `operation`, `outcome`).
- **Метрики person-service**: `person_users_creations_total`, `person_users_updated_total`, `person_users_deleted_total`,
  `person_users_email_conflicts_total`, таймер `person_users_operation_seconds` (теги `operation`, `outcome`).
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
контракта поднимите `person-client-release` в `gradle/libs.versions.toml`. Версия с суффиксом `-SNAPSHOT` уходит в
`maven-snapshots` (адрес выводится из `nexusUrl`, переопределяется `nexusSnapshotsUrl` или `NEXUS_SNAPSHOTS_URL`).

В каталоге две версии: `person-client-release` — что публикуется (сейчас 3.1.0), `person-client` — на чём работает
individuals-api (3.0.0). Они разведены намеренно: новый контракт не должен менять сборку потребителя.
