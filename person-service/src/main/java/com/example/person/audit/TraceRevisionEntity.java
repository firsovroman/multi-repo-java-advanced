package com.example.person.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

/**
 * Ревизия Envers с trace_id: по ревизии можно найти запрос в Tempo.
 */
@Entity
@Table(name = "revinfo")
@RevisionEntity(TraceRevisionListener.class)
public class TraceRevisionEntity {

    @Id
    @RevisionNumber
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "revinfo_seq")
    @SequenceGenerator(name = "revinfo_seq", sequenceName = "revinfo_seq", allocationSize = 1)
    private Long rev;

    @RevisionTimestamp
    @Column(nullable = false)
    private Long revtstmp;

    @Column(name = "trace_id", length = 32)
    private String traceId;

    public Long getRev() {
        return rev;
    }

    public Long getRevtstmp() {
        return revtstmp;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
