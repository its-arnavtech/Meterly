package com.meterly.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void bindingIsScopedAndRestored() {
        assertThat(TenantContext.current()).isEmpty();
        TenantContext.runAs(A, () -> {
            assertThat(TenantContext.require()).isEqualTo(A);
            TenantContext.runAs(B, () -> assertThat(TenantContext.require()).isEqualTo(B));
            assertThat(TenantContext.require()).isEqualTo(A);
        });
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void bindingIsClearedEvenWhenTheActionThrows() {
        assertThatThrownBy(() -> TenantContext.runAs(A, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void requireFailsClosedWithoutATenant() {
        assertThatThrownBy(TenantContext::require).isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void virtualThreadsDoNotInheritTheCallersTenant() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> seenInChild = TenantContext.callAs(A, () ->
                    executor.submit(() -> TenantContext.current().isPresent()));
            assertThat(seenInChild.get()).isFalse();
        }
    }
}
