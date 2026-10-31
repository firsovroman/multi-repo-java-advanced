CREATE SEQUENCE person.revinfo_seq;

CREATE TABLE person.revinfo (
    rev      BIGINT PRIMARY KEY,
    revtstmp BIGINT NOT NULL,
    trace_id VARCHAR(32)
);

-- Строки DEL содержат только идентификатор, поэтому все остальные колонки допускают NULL
CREATE TABLE person.addresses_aud (
    id           UUID     NOT NULL,
    rev          BIGINT   NOT NULL REFERENCES person.revinfo (rev),
    revtype      SMALLINT NOT NULL,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    country_id   INTEGER,
    address_line VARCHAR(128),
    zip_code     VARCHAR(32),
    city         VARCHAR(64),
    state        VARCHAR(64),
    archived_at  TIMESTAMP,
    version      BIGINT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE person.users_aud (
    id             UUID     NOT NULL,
    rev            BIGINT   NOT NULL REFERENCES person.revinfo (rev),
    revtype        SMALLINT NOT NULL,
    email          VARCHAR(1024),
    email_mod      BOOLEAN,
    created_at     TIMESTAMP,
    created_at_mod BOOLEAN,
    updated_at     TIMESTAMP,
    updated_at_mod BOOLEAN,
    first_name     VARCHAR(64),
    first_name_mod BOOLEAN,
    last_name      VARCHAR(64),
    last_name_mod  BOOLEAN,
    filled         BOOLEAN,
    filled_mod     BOOLEAN,
    address_id     UUID,
    address_id_mod BOOLEAN,
    individual_mod BOOLEAN,
    version        BIGINT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE person.individuals_aud (
    id                  UUID     NOT NULL,
    rev                 BIGINT   NOT NULL REFERENCES person.revinfo (rev),
    revtype             SMALLINT NOT NULL,
    user_id             UUID,
    user_id_mod         BOOLEAN,
    passport_number     VARCHAR(32),
    passport_number_mod BOOLEAN,
    phone_number        VARCHAR(32),
    phone_number_mod    BOOLEAN,
    verified_at         TIMESTAMP,
    verified_at_mod     BOOLEAN,
    archived_at         TIMESTAMP,
    archived_at_mod     BOOLEAN,
    status              VARCHAR(32),
    status_mod          BOOLEAN,
    version             BIGINT,
    PRIMARY KEY (id, rev)
);

CREATE INDEX idx_person_addresses_aud_rev ON person.addresses_aud (rev);
CREATE INDEX idx_person_users_aud_rev ON person.users_aud (rev);
CREATE INDEX idx_person_individuals_aud_rev ON person.individuals_aud (rev);
