CREATE UNIQUE INDEX uk_users_email_lower ON person.users (lower(email));

-- Индекс по lower(email) строже и заменяет обе проверки из V001
ALTER TABLE person.users DROP CONSTRAINT users_email_key;
DROP INDEX person.idx_person_users_email;
