package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterly.tenant.auth.ExpiredRefreshTokenPurge;
import com.meterly.testsupport.MeterlyPostgres;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The housekeeping job (and the V11 SECURITY DEFINER function it needs) really deletes expired tokens
 * across tenants while leaving live sessions alone.
 */
class RefreshTokenPurgeIT extends AbstractTenantServiceIT {

    @Autowired
    private ExpiredRefreshTokenPurge purge;

    @Test
    void deletesOnlyExpiredTokens() {
        Session session = signup("Acme Corp", "admin@acme.test");
        JdbcTemplate owner = MeterlyPostgres.ownerJdbc();
        owner.update("""
                        INSERT INTO refresh_token (id, tenant_id, user_id, token_hash, family_id, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), session.tenantId(), session.userId(), "expired-token-hash", UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        assertThat(countTokens(owner)).isEqualTo(2);

        assertThat(purge.purgeNow()).isEqualTo(1);

        assertThat(countTokens(owner)).isEqualTo(1);
        assertThat(purge.purgeNow()).isZero();
        // The live session still works.
        assertThat(post("/v1/auth/refresh", null, refreshCookie(session.refreshToken())).getStatusCode().value())
                .isEqualTo(200);
    }

    private static int countTokens(JdbcTemplate owner) {
        return owner.queryForObject("SELECT count(*) FROM refresh_token", Integer.class);
    }
}
