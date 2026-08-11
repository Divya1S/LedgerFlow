package com.ledgerflow.common.web;

import java.io.IOException;
import java.time.Duration;

import com.ledgerflow.common.cache.RedisSafeCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-user fixed-window rate limit on the money-writing endpoints. Counters
 * live in Redis (shared across instances); when Redis is down the limiter
 * fails OPEN because the ledger's correctness never depended on it, and a
 * Redis outage must not become an API outage.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisSafeCache redis;
    private final int moneyRequestsPerMinute;

    public RateLimitFilter(RedisSafeCache redis,
                           @Value("${ledgerflow.ratelimit.money-requests-per-minute:30}") int moneyRequestsPerMinute) {
        this.redis = redis;
        this.moneyRequestsPerMinute = moneyRequestsPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        boolean moneyEndpoint = path.equals("/api/v1/transfers")
                || path.equals("/api/v1/payments")
                || path.startsWith("/api/v1/payments/")
                || (path.startsWith("/api/v1/accounts/")
                    && (path.endsWith("/deposits") || path.endsWith("/withdrawals")));
        return !moneyEndpoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            long window = System.currentTimeMillis() / 60_000;
            long count = redis.incrementWindow("rl:money:" + auth.getName() + ":" + window,
                    Duration.ofMinutes(1));
            if (count > moneyRequestsPerMinute) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.setHeader("Retry-After", "60");
                response.getWriter().write(
                        "{\"status\":429,\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests, retry later\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
