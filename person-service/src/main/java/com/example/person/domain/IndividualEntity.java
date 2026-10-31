package com.example.person.domain;

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

@Entity
@Audited(withModifiedFlag = true)
@Table(name = "individuals")
public class IndividualEntity {

    /** Начальный статус; остальные значения появятся вместе с логикой верификации. */
    public static final String STATUS_NEW = "NEW";

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserEntity user;

    @Column(name = "passport_number", length = 32)
    private String passportNumber;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(nullable = false, length = 32)
    private String status = STATUS_NEW;

    @Audited(withModifiedFlag = false)
    @Version
    private Long version;

    protected IndividualEntity() {
    }

    public IndividualEntity(UUID id, UserEntity user) {
        this.id = id;
        this.user = user;
    }

    public UUID getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getPassportNumber() {
        return passportNumber;
    }

    public void setPassportNumber(String passportNumber) {
        this.passportNumber = passportNumber;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public String getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }
}
