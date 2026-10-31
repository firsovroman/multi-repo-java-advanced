package com.example.person.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Корень агрегата. Идентификатор создаёт приложение, он же {@code user_uid} платформы.
 * Удаление идёт через каскады JPA, а не через ON DELETE CASCADE из V001: Envers видит только операции
 * Hibernate, и удаление на стороне БД не оставило бы ревизии DEL у individual.
 * Колонка {@code keycloak_user_id} намеренно не отображается: связь с Keycloak хранится в Keycloak.
 */
@Entity
@Audited(withModifiedFlag = true)
@Table(name = "users")
public class UserEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 1024)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "first_name", nullable = false, length = 64)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 64)
    private String lastName;

    @Column(nullable = false)
    private boolean filled;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "address_id")
    private AddressEntity address;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private IndividualEntity individual;

    @Audited(withModifiedFlag = false)
    @Version
    private Long version;

    protected UserEntity() {
    }

    public UserEntity(UUID id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * Заполнен ли пользователь: есть адрес и individual с паспортом и телефоном.
     */
    public void recalculateFilled() {
        filled = address != null && individual != null
                && hasText(individual.getPassportNumber()) && hasText(individual.getPhoneNumber());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public boolean isFilled() {
        return filled;
    }

    public AddressEntity getAddress() {
        return address;
    }

    public void setAddress(AddressEntity address) {
        this.address = address;
    }

    public IndividualEntity getIndividual() {
        return individual;
    }

    public void setIndividual(IndividualEntity individual) {
        this.individual = individual;
    }

    public Long getVersion() {
        return version;
    }
}
