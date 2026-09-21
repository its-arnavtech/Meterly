package com.meterly.common.tenant;

/** Thrown when tenant-scoped work is attempted without a bound {@link TenantContext}. */
public class MissingTenantContextException extends IllegalStateException {

    public MissingTenantContextException() {
        super("No tenant is bound to the current thread; wrap the call in TenantContext.runAs(...)");
    }
}
