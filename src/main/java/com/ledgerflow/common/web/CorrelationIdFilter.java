package com.ledgerflow.common.web;

import java.io.IOException;
import java.util.UUID;

import com.ledgerflow.common.id.Uuid7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a client-provided X-Correlation-Id (must be a UUID) or generates
 * one, exposes it via MDC for structured logs and echoes it on the response,
 * so one ID follows a request across API -> SQL -> outbox -> Kafka consumers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UUID correlationId = parseOrGenerate(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId.toString());
        response.setHeader(HEADER, correlationId.toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private UUID parseOrGenerate(String header) {
        if (header == null || header.isBlank()) {
            return Uuid7.generate();
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            return Uuid7.generate();
        }
    }

    public static UUID current() {
        String value = MDC.get(MDC_KEY);
        return value != null ? UUID.fromString(value) : Uuid7.generate();
    }
}
