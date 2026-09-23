package com.meterly.tenant.auth;

import com.meterly.tenant.support.JdbcColumns;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes refresh tokens whose lifetime has ended. Rotation inserts a row per renewal, so without this the
 * table would grow forever. The delete spans tenants and therefore goes through the SECURITY DEFINER
 * function {@code auth_purge_expired_refresh_tokens} (V11); it is idempotent, so replicas may overlap and
 * no cluster-wide lock is needed. Set the cron to {@code -} to disable.
 */
@Component
public class ExpiredRefreshTokenPurge {

    private static final Logger log = LoggerFactory.getLogger(ExpiredRefreshTokenPurge.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public ExpiredRefreshTokenPurge(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${meterly.tenant.refresh-token-purge-cron:0 7 * * * *}", zone = "UTC")
    public void purge() {
        long deleted = purgeNow();
        if (deleted > 0) {
            log.info("Purged {} expired refresh token(s)", deleted);
        }
    }

    /** Runs the purge immediately and returns how many rows were deleted. */
    public long purgeNow() {
        return jdbc.sql("SELECT auth_purge_expired_refresh_tokens(:expiredBefore)")
                .param("expiredBefore", JdbcColumns.timestamp(clock.instant()))
                .query(Long.class)
                .single();
    }
}
