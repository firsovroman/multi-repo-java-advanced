# CONTEXT — multi-repo-java-advanced (Payment Platform)

Документ для людей и LLM-ассистентов: что это за проект, кто за что отвечает и какие правила нельзя нарушать.

## Платформа

Учебная платёжная платформа в монорепозитории. Модуль 1 закладывает внешний входной слой; следующие модули
добавят person-service, transaction-service, payment-service, webhook-collector-service и notification-service.

## Модули и ответственность

| Модуль | Ответственность | Состояние |
|---|---|---|
| `individuals-api` | внешний входной слой: регистрация, вход, refresh, /me; оркестрация Keycloak и person-service; валидация токенов | реализован |
| `person-service` | доменный пользователь, адрес, индивидуальные данные, транзакционный CRUD, аудит, миграции | контракт + миграции; реализация — модуль 2 |
| `person-client` | HTTP Service Client и DTO person-service, сгенерированные из OpenAPI; публикуется как артефакт | реализован |

## Источники истины

- **person-service** — доменная истина о пользователе. Его `userId` в контрактах (`userUid` в коде и журнале, в Keycloak — атрибут `user_uid`) является
  сквозным идентификатором платформы.
- **Keycloak** — учётная запись, пароль, токены, роли, аутентификационные атрибуты.
- **individuals-api** ничего не хранит, только координирует процесс.

## Инварианты (не нарушать)

1. Контракт пишется раньше кода. HTTP-контракты — только OpenAPI (`*/openapi/*.yaml`); DTO генерируются
   (`openApiGenerate`), вручную не пишутся. Сырые `Map` вместо существующих OpenAPI-моделей запрещены.
2. Межмодульные зависимости — только через опубликованные артефакты (`com.example:person-client`),
   а не через `project(...)` или исходники соседнего модуля.
3. В бизнес-слое используется только `userUid`. Keycloak `sub` (`keycloakUserId`) — техническая связка,
   главным идентификатором он не становится.
4. Слои: `controller -> service -> gateway -> внешняя система`. Контроллеры без логики и без прямых вызовов Keycloak.
5. Регистрация: person-service -> Keycloak (создание пользователя с атрибутом `user_uid`, `reset-password`)
   -> вход через token endpoint. В Keycloak — только после успешного доменного шага.
6. Частичный сбой не скрывается: если person создан, а Keycloak не завершил регистрацию — ERROR-запись
   `event=INCONSISTENT_REGISTRATION` с `userUid` и ответ 502 `REGISTRATION_INCONSISTENT`. Полноценная компенсация —
   в следующих модулях.
7. Пути `/api/v1/persons/registration`, `/api/v1/persons/{userUid}`, `/api/v1/persons/by-email` не переименовываются.
8. Применённые миграции Flyway не редактируются — только новые `V00N__*.sql` поверх.

## Устройство individuals-api

Пакет `com.example.individuals`:

| Пакет | Содержимое |
|---|---|
| `api`, `api.model` | сгенерировано из `individuals-api.yaml` (интерфейс `AuthApi`, DTO) — не редактировать |
| `controller` | `AuthController implements AuthApi` |
| `service` | `AuthService` — оркестрация и метрики попыток |
| `gateway` | `PersonGateway` — вызовы person-service через `PersonsApi` (HTTP Service Client) |
| `keycloak` | `KeycloakGateway` (Admin REST API), `KeycloakTokenClient` (token endpoint), `KeycloakAdminTokenProvider` (client_credentials, кеш) |
| `error` | исключения с HTTP-статусом и `ErrorCode`, `GlobalExceptionHandler`, `ErrorResponseFactory` (traceId) |
| `security` | resource server (JWT Keycloak), JSON-ответы 401/403, разбор claims |
| `metrics` | счётчики `auth.*`, таймеры `external.*.requests` |
| `logging` | `LogContext` (поля `userUid`/`errorCode` запроса: атрибут exchange + Reactor Context), журнал запросов (`WebFilter`) |
| `config` | HTTP-клиенты (`@ImportHttpServices` с `WEB_CLIENT`, WebClient для Keycloak), `KeycloakProperties` |

Стек реактивный: Spring WebFlux на Reactor Netty, весь путь запроса — `Mono` без блокировок. HTTP-клиенты строятся
на автоконфигурированном `WebClient.Builder`, поэтому получают метрики и передачу trace context;
`PersonsApi` из person-client (с 2.0.0) возвращает `Mono`. Resource server — `SecurityWebFilterChain` и `ReactiveJwtDecoder`.

## Keycloak

Realm `payment-platform` (`realm-export.json`): confidential client `individuals-api`
(direct access grants + service account с ролями `realm-management`: `manage-users`, `view-users`, `query-users`),
mapper атрибута `user_uid` в access/id token и userinfo, realm-роль `USER` входит в default roles,
атрибут `user_uid` объявлен в user profile (редактирует только admin).

В docker Keycloak запущен с `KC_HOSTNAME=http://localhost:8080`: issuer в токенах — внешний адрес, а individuals-api
ходит в Keycloak по `http://keycloak:8080` и проверяет подпись через `jwk-set-uri`, issuer — через `issuer-uri`.

## Технологии

Java 25, Spring Boot 4.1 (Spring Framework 7, WebFlux, Jackson 3), Gradle 9 (Kotlin DSL, общий version catalog),
OpenAPI Generator 7.26 (`spring` / `spring-http-interface`, `useSpringBoot4`, `useJackson3`, `reactive`), Keycloak 26.8,
Micrometer + OpenTelemetry, Prometheus, Tempo, Grafana, Testcontainers 2, WireMock 3, JaCoCo, Flyway, PostgreSQL 18.

## Известные особенности

- JDK HttpClient пробует апгрейд на HTTP/2 (h2c); WireMock рвёт такой поток, поэтому в заглушке и тестах h2c отключён
  (`--disable-http2-plain`, `http2PlainDisabled(true)`).
- person-client публикуется в Nexus (`maven-releases`, локально http://localhost:8091); адрес и учётные данные — только
  в `~/.gradle/gradle.properties` или переменных окружения, не в репозитории (см. README).
- Сжатие ответов в WebClient выключено (`HttpClientsConfig`): распаковка gzip в Netty 4.2 на JDK 25 падает
  с `UnsupportedOperationException: ByteBuffer derived from closeable shared sessions not supported`.
- traceId/spanId в MDC восстанавливаются из Reactor Context (`spring.reactor.context-propagation=auto`); traceId в
  `ErrorResponse` берётся из наблюдения HTTP-запроса, привязанного к exchange.
