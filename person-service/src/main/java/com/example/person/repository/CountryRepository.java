package com.example.person.repository;

import com.example.person.domain.CountryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CountryRepository extends JpaRepository<CountryEntity, Integer> {

    Optional<CountryEntity> findByAlpha3(String alpha3);
}
