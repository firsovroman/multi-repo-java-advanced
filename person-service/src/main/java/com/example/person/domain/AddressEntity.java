package com.example.person.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.Instant;
import java.util.UUID;

@Entity
@Audited
@Table(name = "addresses")
public class AddressEntity {

    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id")
    private CountryEntity country;

    @Column(name = "address_line", nullable = false, length = 128)
    private String addressLine;

    @Column(name = "zip_code", length = 32)
    private String zipCode;

    @Column(nullable = false, length = 64)
    private String city;

    @Column(length = 64)
    private String state;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    private Long version;

    protected AddressEntity() {
    }

    public AddressEntity(UUID id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() {
        return id;
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

    public CountryEntity getCountry() {
        return country;
    }

    public void setCountry(CountryEntity country) {
        this.country = country;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getZipCode() {
        return zipCode;
    }

    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Long getVersion() {
        return version;
    }
}
