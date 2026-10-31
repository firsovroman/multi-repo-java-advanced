package com.example.person.audit;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.hibernate.envers.RevisionListener;
import org.springframework.stereotype.Component;

/**
 * Записывает в ревизию traceId текущего span. Hibernate берёт слушатель из контекста Spring.
 */
@Component
public class TraceRevisionListener implements RevisionListener {

    private final Tracer tracer;

    public TraceRevisionListener(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void newRevision(Object revisionEntity) {
        Span span = tracer.currentSpan();
        if (span != null) {
            ((TraceRevisionEntity) revisionEntity).setTraceId(span.context().traceId());
        }
    }
}
