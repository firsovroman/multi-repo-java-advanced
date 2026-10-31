package com.example.person.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Справочник стран: наполняется миграцией V002, API его не меняет.
 */
@Entity
@Immutable
@Table(name = "countries")
public class CountryEntity {

    @Id
    private Integer id;

    @Column(nullable = false, length = 64)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 2)
    private String alpha2;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String alpha3;

    protected CountryEntity() {
    }

    public CountryEntity(Integer id, String name, String alpha2, String alpha3) {
        this.id = id;
        this.name = name;
        this.alpha2 = alpha2;
        this.alpha3 = alpha3;
    }

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAlpha2() {
        return alpha2;
    }

    public String getAlpha3() {
        return alpha3;
    }
}
