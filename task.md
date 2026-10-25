===== Page 1 =====

25.10.2026, 07:56 Практической задание модуля 1

Задание

Практической задание модуля 1
Описание задания История проверки

Практическая работа модуля 1
Тема: individuals-api как внешний оркестратор аутентификации и регистрации пользователя в монорепозитории
Роль модуля в курсе: данный модуль закладывает внешний входной слой всей платформы. Именно здесь студент впервые связывает OpenAPI-контракты, межсервисные вызовы, внешнюю систему аутентификации, наблюдаемость, публикацию артефактов и тестирование интеграций. Этот модуль нельзя рассматривать изолированно: его задача - не только запустить аутентификацию, но и создать архитектурную опору для модулей person-service, transaction-service, payment-service, webhook-collector-service и notification-service.

Цель работы
Реализовать микросервис individuals-api, который:

принимает внешние запросы на регистрацию, вход, обновление токена и получение текущего пользователя;

оркестрирует создание доменного пользователя через контракт person-service;

регистрирует учётную запись в Keycloak только после успешного доменного шага;

===== Page 2 =====

сохраняет доменный идентификатор user_uid как пользовательский атрибут в Keycloak;

возвращает клиенту стандартизированные ответы и ошибки;

публикует метрики и трассы;

поставляется как модуль монорепозитория с контрактами, Docker Compose, тестами и артефактами для повторного использования.

Что должно получиться по итогам модуля
После завершения практики в репозитории должны появиться:

Артефакт | Обязателен | Назначение
person-client | Да | Общие DTO, модели ошибок, клиентские контракты, OpenAPI-артефакты
individuals-api | Да | Оркестратор аутентификации и регистрации
person-service/openapi/person-service.yaml | Да | Финальный контракт будущего сервиса пользователей
person-service/src/main/resources/db/migration | Да | Миграции Flyway, которые будут использоваться в следующем модуле
docker-compose.yml | Да | Локальный запуск инфраструктуры и модуля
infra/prometheus/prometheus.yml | Да | Сбор метрик с individuals-api
infra/tempo/tempo.yml | Да | Приём трасс OTLP
infra/grafana/provisioning/... | Да | Источники данных и базовые дашборды
README.md | Да | Сборка, запуск, тестирование, примеры вызовов
CONTEXT.md | Да | Контекст проекта для людей и LLM
Postman-коллекция | Да | Проверка сценариев руками

===== Page 3 =====

Артефакт | Обязателен | Назначение
Юнит-тесты | Да | Проверка бизнес-логики и граничных ошибок
Интеграционные тесты | Да | Проверка Keycloak, HTTP-контракта и трассировки
Публикация person-client в Nexus | Да | Повторное использование в следующих модулях

Стандарт курса для технологий и версий
В этом курсе для данного модуля применяется следующий стандарт.

Spring Boot фиксируется на актуальной стабильной ветке 4.0.x; на дату подготовки блока документация отражает стабильный релиз 4.0.6 и preview-ветку 4.1.0-RC1. Boot 4.0.6 требует Java 17+ и совместим до Java 26, поэтому курс фиксирует Java 25 как LTS-цель: это свежая, поддерживаемая и уже production-ready база. Для сборки используйте Gradle Wrapper; Spring Boot 4 документирует поддержку Gradle 8.14+ и 9.x.

OpenAPI обязателен как источник истины для HTTP-контрактов. Спецификация OpenAPI определяет машинно-читаемое описание HTTP API, которое позволяет людям и программам понимать возможности сервиса без чтения исходного кода. Плагин OpenAPI Generator для Gradle предоставляет DSL и задачи openApiGenerate, openApiValidate и связанные с ними механизмы генерации.

Для межсервисного HTTP-взаимодействия мы принимаем следующий стандарт:

• DTO и контракты генерируются по OpenAPI
• прикладной транспортный адаптер строится через Spring HTTP Service Clients и RestClient.

Это лучше согласуется с современной документацией Spring Framework и позволяет не тащить в каждый модуль отдельную "культуру Feign".

Границы ответственности модулей
В рамках всего курса нужно жёстко удерживать следующие роли:

===== Page 4 =====

Модуль | Ответственность
individuals-api | внешний входной слой, оркестрация регистрации и аутентификации, вызовы Keycloak и person-service, валидация токенов на входе
person-service | доменный пользователь, адрес, индивидуальные данные, транзакционный CRUD, аудит, миграции, доменная БД

Ключевое правило:

• person-service - источник доменной истины о пользователе
• Keycloak - источник истины об учётной записи, токенах, ролях и аутентификационных атрибутах
• individuals-api ничего не хранит как доменный сервис, а только координирует процесс.

Что реализуется в этом модуле
В модуле должны быть реализованы следующие внешние методы individuals-api:

Метод | Назначение | Авторизация
POST /api/v1/auth/registration | регистрация нового пользователя | не требуется
POST /api/v1/auth/login | вход по email и паролю | не требуется
POST /api/v1/auth/refresh-token | обновление токена доступа | не требуется
GET /api/v1/auth/me | получение текущего пользователя | Bearer Token

Бизнес-сценарий регистрации
Сценарий регистрации должен работать так:

individuals-api принимает запрос регистрации.

===== Page 5 =====

Проверяет формат данных и согласованность пароля.

Вызывает person-service по HTTP-контракту для создания доменного пользователя.

Получает user_uid.

Получает административный токен Keycloak.

Создаёт пользователя в Keycloak через Admin REST API POST /admin/realms/{realm}/users.

Устанавливает пароль через PUT /admin/realms/{realm}/users/{user-id}/reset-password.

Записывает user_uid как пользовательский атрибут Keycloak.

Выполняет обычный вход через OIDC token endpoint /realms/{realm-name}/protocol/openid-connect/token.

Возвращает клиенту access token, refresh token, срок жизни токена и user_uid.

Keycloak документирует token endpoint OIDC и Admin REST API для создания пользователя и установки пароля. Управление пользовательскими атрибутами также поддерживается штатно.

Требования к окружению
Компонент | Требование
JDK | Java 25
Сборка | Gradle Wrapper
Контейнеры | Docker Engine + Docker Compose v2
ОС | Linux / macOS / Windows с WSL2
Nexus | доступен по внутреннему адресу организации или локально
Порты | свободны порты, перечисленные в таблице ниже

Рекомендуемая структура монорепозитория
===== Page 6 =====

1 proselyte-payment-platform/
2 settings.gradle.kts
3 build.gradle.kts
4 gradle/libs.versions.toml
5 docker-compose.yml
6 README.md
7 CONTEXT.md
8 infra/
9 prometheus/prometheus.yml
10 tempo/tempo.yml
11 grafana/
12 provisioning/
13 dashboards/
14 individuals-api/
15 build.gradle.kts
16 Dockerfile
17 src/main/java/com/example/individuals/
18 controller/
19 service/
20 gateway/
21 keycloak/
22 config/
23 metrics/
24 error/
25 src/main/resources/

===== Page 7 =====

26 | | | application.yml
27 | | | application-docker.yml
28 | | | logback-spring.xml
29 | | | realm/
30 | | | realm-export.json
31 | | | src/test/java/com/example/individuals/
32 | | | unit/
33 | | | integration/
34 | | | person-service/
35 | | | build.gradle.kts
36 | | | openapi/person-service.yaml
37 | | | src/main/resources/db/migration/
38 | | | v001_init_person_schema.sql
39 | | | v002_seed_countries.sql

Рекомендации по зависимостям
Корневой проект
Не является Maven/Gradle проектом, это просто пакет, который играет роль Git root.

Все межмодульные зависимости задаются только через зависимость на опубликованные артефакты Nexus.

Никакие DTO руками не пишутся, они всегда описаны в OpenAPI каждого сервиса и публикуются с клиентов в Nexus.

Модуль individuals-api
Минимальный набор зависимостей:

===== Page 8 =====

1 dependencies {
2 implementation("org.springframework.boot:spring-boot-starter-web")
3 implementation("org.springframework.boot:spring-boot-starter-security")
4 implementation("org.springframework.boot:spring-boot-starter-validation")
5 implementation("org.springframework.boot:spring-boot-starter-actuator")
6 implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
7
8 implementation("org.springframework.boot:spring-boot-starter-json")
9 implementation("org.springframework:spring-web")
10
11 testImplementation("org.springframework.boot:spring-boot-starter-test")
12 testImplementation("org.springframework.boot:spring-boot-testcontainers")
13 testImplementation("org.testcontainers:junit-jupiter")
14 testImplementation("org.testcontainers:postgresql")
15 }
16

Для тестовой интеграции с контейнерами Spring Boot рекомендует модуль spring-boot-testcontainers. @ServiceConnection позволяет связать тестовые сервисы с приложением без ручной простановки URL для поддерживаемых контейнеров. Для Keycloak, как для внешнего HTTP-сервиса, допустимо использовать обычный GenericContainer и DynamicPropertySource.

Контрактно-ориентированная разработка
Правило контракта
Контракт всегда пишется раньше кода. Сначала создаются:

===== Page 9 =====

individuals-api.yaml

person-service.yaml

единая схема ошибок

примеры полезных нагрузок для всех внешних методов

Задачи генератора
В сборке должны использоваться как минимум следующие задачи:

• openApiValidate
• openApiGenerate

Плагин OpenAPI Generator предоставляет эти задачи прямо в Gradle и поддерживает декларативную настройку через DSL.

Пример настройки:

1 openApiGenerate {
2 generatorName.set("java")
3 inputSpec.set("
r
o
o
t
D
i
r
/
c
o
m
m
o
n
/
s
r
c
/
m
a
i
n
/
r
e
s
o
u
r
c
e
s
/
o
p
e
n
a
p
i
/
i
n
d
i
v
i
d
u
a
l
s
−
a
p
i
.
y
a
m
l
"
)
4
o
u
t
p
u
t
D
i
r
.
s
e
t
(
"
rootDir/common/src/main/resources/openapi/individuals−api.yaml")4outputDir.set("buildDir/generated-sources/openapi")
5 apiPackage.set("com.example.generated.api")
6 modelPackage.set("com.example.generated.model")
7 invokerPackage.set("com.example.generated.invoker")
8 }

Практическое правило для курса
В бизнес-слое запрещено использовать «сырые» JSON-словари и анонимные карты там, где уже существует OpenAPI-модель. Любой внешний контракт должен проходить через DTO, полученный из спецификации.

===== Page 10 =====

25.10.2026, 07:56 Практической задание модуля 1

Требования к OpenAPI для individuals-api

Минимальный контракт должен включать:
• RegistrationRequest
• LoginRequest
• RefreshTokenRequest
• TokenResponse
• CurrentUserResponse
• ErrorResponse

Рекомендуемая структура ErrorResponse для всего курса:

1 ErrorResponse:
2 type: object
3 required:
4 - timestamp
5 - path
6 - status
7 - error
8 - message
9 - traceId
10 properties:
11 timestamp:
12 type: string
13 format: date-time
14 path:
15 type: string
16 status:

===== Page 11 =====

17 type: integer
18 error:
19 type: string
20 message:
21 type: string
22 traceId:
23 type: string
24 details:
25 type: array
26 items:
27 type: string

Требования к person-service контракту уже в этом модуле
Хотя полноценная реализация person-service - предмет следующего модуля, в текущем модуле обязательно должны быть готовы:

финальная OpenAPI-спецификация сервиса пользователей;

миграции Flyway;

DTO через автогенерацию в NexusOti;

HTTP-контракт, который использует individuals-api.

Минимальные методы, которые надо зафиксировать уже сейчас:

Метод | Назначение
POST /api/v1/persons/registration | создать доменного пользователя и вернуть user_uid
GET /api/v1/persons/{userUid} | получить пользователя по доменному идентификатору

===== Page 12 =====

25.10.2026, 07:56

Метод | Назначение
GET /api/v1/persons/by-email | получить пользователя по email

Эти операции не должны потом переименовываться без крайней необходимости.

DDL и миграции Flyway
В старом описании DDL есть несколько слабых мест: неоднозначное поле secret_key, обязательные verified_at и archived_at в ситуациях, когда они по смыслу должны быть пустыми до наступления события, а также отсутствие строгих ограничений на email. В этой версии практики используйте исправленный DDL. Это не отклонение от курса, а раннее устранение проблем, которые иначе всплывут во втором модуле.

Flyway рекомендует версионированные миграции, которые применяются строго по порядку, ровно один раз, и не должны переписываться после применения в постоянных средах; изменения вносятся новой миграцией поверх старой.

VOO1_init_person_schema.sql
1 CREATE SCHEMA IF NOT EXISTS person;
2 CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
3
4 CREATE TABLE person.countries (
5 id SERIAL PRIMARY KEY,
6 created_at TIMESTAMP2 NOT NULL DEFAULT now(),
7 updated_at TIMESTAMP2 NOT NULL DEFAULT now(),
8 name VARCHAR(64) NOT NULL,
9 alpha2 CHAR(2) NOT NULL UNIQUE,
10 alpha3 CHAR(3) NOT NULL UNIQUE,
11 status VARCHAR(32) NOT NULL
12 );

===== Page 13 =====

13
14 CREATE TABLE person.addresses (
15 id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
16 created_at TIMESTAMP NOT NULL DEFAULT now(),
17 updated_at TIMESTAMP NOT NULL DEFAULT now(),
18 country_id INTEGER REFERENCES person.countries(id),
19 address_line VARCHAR(128) NOT NULL,
20 zip_code VARCHAR(32),
21 city VARCHAR(64) NOT NULL,
22 state VARCHAR(64),
23 archived_at TIMESTAMP NULL
24 );
25
26 CREATE TABLE person.users (
27 id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
28 email VARCHAR(1024) NOT NULL UNIQUE,
29 created_at TIMESTAMP NOT NULL DEFAULT now(),
30 updated_at TIMESTAMP NOT NULL DEFAULT now(),
31 first_name VARCHAR(64) NOT NULL,
32 last_name VARCHAR(64) NOT NULL,
33 filled BOOLEAN NOT NULL DEFAULT FALSE,
34 address_id UUID REFERENCES person.addresses(id),
35 keycloak_user_id VARCHAR(64) UNIQUE NULL
36 );
37
38 CREATE TABLE person.individuals (

===== Page 14 =====

39 id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
40 user_id UUID NOT NULL UNIQUE REFERENCES person.users(id) ON DELETE CASCADE,
41 passport_number VARCHAR(32),
42 phone_number VARCHAR(32),
43 verified_at TIMESTAMP NULL,
44 archived_at TIMESTAMP NULL,
45 status VARCHAR(32) NOT NULL DEFAULT 'NEW'
46 );
47
48 CREATE INDEX idx_person_users_email ON person.users(email);
49 CREATE INDEX idx_person_individuals_phone_number ON person.individuals(phone_number);

Требования к Keycloak
В модуле необходимо подготовить realm-экспорт или импортируемую конфигурацию, в которой есть:

отдельный realm курса

confidential client для individuals-api

service account для вызова Admin REST API

маррег или иная настройка, позволяющая включать user_uid в токен либо получать его согласованно через /me

роли, необходимые для чтения и управления пользователями.

Keycloak поддерживает:

• создание пользователя в нужном realm
• управление пользовательскими атрибутами
• установку пароля пользователю
• token endpoint OIDC для получения и обновления токенов.

===== Page 15 =====

25.10.2026, 07:56 Практической задание модуля 1

Требования к individuals-api

Обязательная структура слоёв controller -> service -> gateway -> external system

Где:

• controller принимает/возвращает DTO
• service содержит оркестрацию
• gateway инкапсулирует вызовы Keycloak и person-service
• код безопасности и ошибки вынесены в отдельные пакеты.

Обязательные правила реализации

Контроллеры не содержат бизнес-логики.

Контроллеры не вызывают Keycloak напрямую.

В бизнес-слое используется только доменный user_uid, а не внутренние идентификаторы Keycloak.

Идентификатор Keycloak (sub) хранится как техническая связка, но не становится главным идентификатором платформы.

Рекомендуемый алгоритм регистрации

registration request-> validate request-> create domain user in person-service-> get user_uid-> create user in Keycloak-> set password in Keycloak-> store user_uid attribute in Keycloak-> login through token endpoint-> return tokens + user_uid

Политика компенсации

Если доменный пользователь создан, а регистрация в Keycloak не завершилась, сервис должен:

записать структурированную ошибку

пометить инцидент как несогласованную регистрацию

вернуть клиенту ошибку

не скрывать частичный сбой.

В модуле 1 допустим упрощённый вариант компенсации: логирование и явный код ошибки. Полноценные компенсирующие сценарии будут развиваться дальше по курсу.

===== Page 16 =====

25.10.2026, 07:56 Практической задание модуля 1

Стандартизация HTTP-клиентов
Для вызовов person-service используйте RestClient и HTTP Service Clients.

Spring Framework описывает RestClient как синхронный HTTP-клиент с API, а HTTP Service Clients - как аннотированные интерфейсы, за которыми стоят сгенерированные прокси. Это и нужно принять как основной стиль курса.

Пример интерфейса:

1 @HttpExchange("/api/v1/persons")
2 public interface PersonServiceClient {
3
4 @PostExchange("/registration")
5 RegistrationResult createUser(@RequestBody RegistrationCommand request);
6
7 @GetMapping("/userUid")
8 PersonResponse getByUserUid(@PathVariable UUID userUid);
9
10 @GetMapping("/by-email")
11 PersonResponse getByEmail(@RequestBody String email);
12 }
13

Наблюдаемость

Метрики

===== Page 17 =====

1 management:
2 endpoints:
3 web:
4 exposure:
5 include: "health,info,prometheus"
6 endpoint:
7 health:
8 probes:
9 enabled: true

Для метрик используйте Actuator и Prometheus. По умолчанию HTTP наружу выставлен только health; endpoint prometheus нужно явно включить. Spring Boot документирует, что /actuator/prometheus отдает метрики в формате Prometheus, а в prometheus.yml должен быть прописан metrics_path: /actuator/prometheus.

Рекомендуемая конфигурация:

1 management:
2 endpoints:
3 web:
4 exposure:
5 include: "health,info,prometheus"
6 endpoint:
7 health:
8 probes:
9 enabled: true

Минимальный набор прикладных метрик:

Метрика | Тип | Смысл
auth_registration_total | counter | число попыток регистрации
auth_registration_success_total | counter | успешные регистрации
auth_registration_failure_total | counter | неуспешные регистрации
auth_login_total | counter | число логинов

===== Page 18 =====

25.10.2026, 07:56

Метрика | Тип | Смысл
auth_login_failure_total | counter | неуспешные логины
auth_refresh_total | counter | обновления токена
external_keycloak_requests | timer | длительность запросов к Keycloak
external_person_service_requests | timer | длительность запросов к person-service

Трассировка
Spring Boot 4 поддерживает трассировку через Micrometer Tracing и OpenTelemetry. Для OTLP в Boot есть выделенный starter и свойства management.opentelemetry.tracing.export.otlp.*. Автоматическая передача trace context по сети будет работать только если HTTP-клиент построен через автоконфигурированные билдеры Spring.

Рекомендуемая конфигурация:

1 management:
2 tracing:
3 sampling:
4 probability: 1.0
5 opentelemetry:
6 tracing:
7 export:
8 otlp:
9 endpoint: http://tempo:4318/v1/traces

===== Page 19 =====

Темро принимает OTLP HTTP на 4318 и endpoint /v1/traces.

Журналы
Требование модуля: все журналы - в JSON в стандартный вывод. В каждую запись должны попадать:

• имя сервиса;
• traceld;
• spanld;
• путь запроса;
• HTTP-метод;
• код ответа;
• код бизнес-ошибки;
• доменный user_uid, если он известен.

Spring Boot при включённой трассировке добавляет correlation identifiers на основе traceld и spanld, а формат корреляции можно менять через logging.pattern.correlation.

Docker Compose
Ниже приведён рекомендуемый состав локальной инфраструктуры.

Сервис | Назначение | Порт хоста
keycloak-postgres | база Keycloak | 5433:5432
keycloak | аутентификация и токены | 8080:8080
individuals-api | оркестратор | 8081:8081
person-postgres | проверка миграций будущего person-service | 5434:5432
prometheus | сбор метрик | 9090:9090
tempo | приём и хранение трасс | 3200:3200, 4318:4318

===== Page 20 =====

25.10.2026, 07:56

Сервис | Назначение | Порт хоста
grafana | визуализация | 3000:3000

Рекомендуемый docker-compose.yml:

1 services:
2 keycloak-postgres:
3 image: postgres:18
4 environment:
5 POSTGRES_DB: keycloak
6 POSTGRES_USER: keycloak
7 POSTGRES_PASSWORD: keycloak
8 ports:
9 - "5433:5432"
10 healthcheck:
11 test: ["CMD-SHELL", "pg_isready -U keycloak -d keycloak"]
12 interval: 10s
13 timeout: 5s
14 retries: 10
15
16 keycloak:
17 image: quay.io/keycloak/keycloak:latest
18 command: start-dev --import-realm
19 depends_on:
20 keycloak-postgres:

===== Page 21 =====

21 condition: service_healthy
22 environment:
23 KEYCLOAK_ADMIN: admin
24 KEYCLOAK_ADMIN_PASSWORD: admin
25 KC_DB: postgres
26 KC_DB_URL: jdbc:postgresql://keycloak-postgres:5432/keycloak
27 KC_DB_USERNAME: keycloak
28 KC_DB_PASSWORD: keycloak
29 volumes:
30 - ./individuals-api/src/main/resources/realm/realm-export.json:/opt/keycloak/data/import/realm-
31 export.json
32 ports:
33 - "8080:8080"
34
35 person-postgres:
36 image: postgres:18
37 environment:
38 POSTGRES_DB: person
39 POSTGRES_USER: person
40 POSTGRES_PASSWORD: person
41 ports:
42 - "5434:5432"
43 healthcheck:
44 test: ["CMD-SHELL", "pg_isready -U person -d person"]
45 interval: 10s
46 timeout: 5s

===== Page 22 =====

47
48
49 individuals-api:
50 build:
51 context: ./individuals-api
52 depends_on:
53 keycloak:
54 condition: service_started
55 prometheus:
56 condition: service_started
57 tempo:
58 condition: service_started
59 environment:
60 SPRING_PROFILES_ACTIVE: docker
61 ports:
62 - "8081:8081"
63
64 prometheus:
65 image: prom/prometheus:latest
66 volumes:
67 - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
68 ports:
69 - "9090:9090"
70
71 tempo:
72 image: grafana/tempo:latest

===== Page 23 =====

73 command: [ "-config.file=/etc/tempo/tempo.yml" ]
74 volumes:
75 - ./infra/tempo/tempo.yml:/etc/tempo/tempo.yml
76 ports:
77 - "3200:3200"
78 - "4318:4318"
79
80 grafana:
81 image: grafana/grafana:latest
82 depends_on:
83 - prometheus
84 - tempo
85 environment:
86 GF_SECURITY_ADMIN_USER: admin
87 GF_SECURITY_ADMIN_PASSWORD: admin
88 volumes:
89 - ./infra/grafana/provisioning:/etc/grafana/provisioning
90 - ./infra/grafana/dashboards:/var/lib/grafana/dashboards
91 ports:
92 - "3000:3000"

Если вы используете встроенную поддержку Docker Compose в Spring Boot для разделяемых сервисов между несколькими приложениями, документация Boot рекомендует использовать compose.yaml и при общей инфраструктуре выставлять spring.docker.compose.lifecycle-management=start-only, чтобы остановка одного приложения не тушила общие сервисы других

===== Page 24 =====

1 global:
2 scrape_interval: 15s
3
4 scrape_configs:
5 - job_name: "individuals-api"
6 metrics_path: "/actuator/prometheus"
7 static_configs:
8 - targets: ["individuals-api:8081"]

1 server:
2 http_listen_port: 3200
3
4 distributor:
5 receivers:
6 otlp:
7 protocols:
8 http:

===== Page 25 =====

9
10 storage:
11 trace:
12 backend: local
13 local:
14 path: /tmp/tempo/traces
15
16 compactor:
17 compaction:
18 block_retention: 24h

Примеры запросов и ожидаемых ответов

Регистрация

===== Page 26 =====

1 curl -X POST http://localhost:8081/api/v1/auth/registration
2 -H "Content-Type: application/json"
3 -d '{
4 "email": "user@example.com",
5 "password": "Str@ngPass!",
6 "confirmPassword": "Str@ngPass!",
7 "firstName": "Ivan",
8 "lastName": "Ivanov"
9 }'

Ожидаемый ответ:

1 {
2 "userId": "3d6f3532-8b97-4c7b-8a2a-608b6aeb3a7e",
3 "accessToken": "<jwt>",
4 "refreshToken": "<jwt>",
5 "expiresIn": 300,
6 "tokenType": "Bearer"
7 }

Вход

===== Page 27 =====

1 curl -X POST http://localhost:8081/api/v1/auth/login
2 -H "Content-Type: application/json"
3 -d '{
4 "email": "user@example.com",
5 "password": "Str@ngPass!"
6 }'

1 {
2 "userId": "3d6f3532-8b97-4c7b-8a2a-608b6aeb3a7e",
3 "accessToken": "<jwt>",
4 "refreshToken": "<jwt>",
5 "expiresIn": 300,
6 "tokenType": "Bearer"
7 }

Обновление токена

===== Page 28 =====

1 curl -X POST http://localhost:8081/api/v1/auth/refresh-token
2 -H "Content-Type: application/json"
3 -d '{
4 "refreshToken": "<jwt>"
5 }'

Текущий пользователь
1 curl http://localhost:8081/api/v1/auth/me
2 -H "Authorization: Bearer <jwt>"

Ожидаемый ответ:

1 {
2 "userUid": "3d6f3532-8b97-4c7b-8a2a-608b6aeb3a7e",
3 "keycloakUserId": "6d4a6249-75d8-4b8d-aee8-c2f6ddafaa11",
4 "email": "user@example.com",
5 "firstName": "Ivan",
6 "lastName": "Ivanov",
7 "roles": ["USER"],

===== Page 29 =====

8 "emailVerified": false
9 }

Ошибка
1 {
2 "timestamp": "2026-06-08T10:15:30Z",
3 "path": "/api/v1/auth/registration",
4 "status": 409,
5 "error": "CONFLICT",
6 "message": "Пользователь с таким email уже существует",
7 "traceId": "803B448A0489F84084905D3093480352",
8 "details": []
9 }

Тестирование
В тестировании нужно покрыть два слоя:

Юнит-тесты - оркестрация, валидация, преобразование ошибок, обработка конфликтов, разбор ответов внешних систем.

Интеграционные тесты - контейнеры, реальные HTTP-вызовы к Keycloak, проверка токенов, проверка Actuator и трассировки.

Технологический стандарт тестов
Spring Boot содержит отдельный модуль для интеграции с Testcontainers, Testcontainers документирует быстрый старт с JUnit Jupiter, а JUnit User Guide подчёркивает, что основная современная модель тестирования строится вокруг JUnit Jupiter и extension

===== Page 30 =====

25.10.2026, 07:56

model.

Обязательные тест-кейсы

Код | Сценарий | Ожидаемый результат
UT-REG-001 | валидный запрос регистрации | сервис вызывает сначала person-service, затем Keycloak, затем возврат токенов
UT-REG-002 | пароль и подтверждение не совпадают | 400 Bad Request, Keycloak не вызывается
UT-REG-003 | person-service вернул конфликт email | 409 Conflict, Keycloak не вызывается
UT-REG-004 | пользователь создан в person-service, но Keycloak недоступен | 502/503, фиксируется частичный сбой
UT-LOG-001 | успешный логин | возвращаются access/refresh token
UT-LOG-002 | неверный пароль | 401 Unauthorized
UT-REF-001 | успешное обновление токена | новый access token возвращён
UT-ME-001 | валидный Bearer token | возвращаются данные текущего пользователя
IT-KC-001 | регистрация с реальным Keycloak-контейнером | пользователь появляется в realm
IT-KC-002 | после регистрации у пользователя есть атрибут user_uid | атрибут найден в Keycloak

===== Page 31 =====

Код | Сценарий | Ожидаемый результат
IT-KC-003 | /api/v1/auth/login использует реальный token endpoint | возвращён реальный JWT
IT-OBS-001 | /actuator/prometheus доступен | Prometheus-метрики читаются
IT-OBS-002 | после запроса создаётся trace | трасса видна в Tempo/Grafana
IT-OBS-003 | журналы содержат traceld и spanld | корреляция работает
IT-DB-001 | миграции person-service применяются к PostgreSQL | Flyway проходит успешно

Критерии приёмки
Работа считается принятой, если выполнены все условия ниже.

Критерий | Обязателен
монорепозиторий содержит individuals-api, person-service | Да
OpenAPI-контракты есть и проходят openApiValidate | Да
регистрация работает по окрестрационному сценарию | Да
user_uid записывается в Keycloak как атрибут | Да
/login, /refresh-token, /me работают | Да
HTTP клиент с DTO публикуется в Nexus | Да

===== Page 32 =====

25.10.2026, 07:56

Критерий | Обязателен
docker compose up --build поднимает инфраструктуру и приложение | Да
/actuator/health и /actuator/prometheus доступны | Да
трассы видны в Grafana/Tempo | Да
есть unit и integration tests | Да
покрытие основного сценария не ниже 80% по ключевым сервисам | Да
README и CONTEXT.md оформлены | Да

Контрольный чек-лист для студента
Проверка | Статус
OpenAPI описывает все внешние методы и ошибки | ☐
DTO не написаны вручную поверх уже описанного контракта | ☐
individuals-api не хранит доменные данные пользователя | ☐
person-service обозначен как источник доменной истины | ☐
user_uid сквозной и не заменяется на sub | ☐
все внешние вызовы вынесены в шлюзы | ☐
метрики доступны через /actuator/prometheus | ☐
трассы уходят по OTLP в Tempo | ☐
журналы в JSON и содержат корреляцию | ☐

===== Page 33 =====

25.10.2026, 07:56

Проверка | Статус
есть как минимум один интеграционный тест с Keycloak | ☐
миграции person-service приложены и исполняемы | ☐
person-client публикуется в Nexus | ☐