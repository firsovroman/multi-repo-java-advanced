package com.example.person.repository;

import com.example.person.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    /**
     * Запрос идёт через индекс {@code uk_users_email_lower}; {@code email} должен быть в нижнем регистре.
     */
    @Query("select u from UserEntity u where lower(u.email) = :email")
    Optional<UserEntity> findByLowerEmail(@Param("email") String email);
}
