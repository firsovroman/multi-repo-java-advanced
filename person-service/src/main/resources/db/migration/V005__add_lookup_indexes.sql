CREATE INDEX idx_person_addresses_country_id ON person.addresses (country_id);
CREATE INDEX idx_person_users_address_id ON person.users (address_id);
