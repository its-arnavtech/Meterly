package com.meterly.ingestion.ingest;

import java.util.UUID;

/**
 * Abstracts "is this an active resource of the authenticated tenant" so {@link IngestionService} does
 * not need to know whether the caller authenticated with an API key or a JWT: each has its own cache and
 * refresh-once strategy (see {@link ApiKeyResourceIdSource}, {@link JwtResourceIdSource}).
 */
interface ResourceIdSource {

    boolean contains(UUID resourceId);

    /** Bypasses the cache once and re-checks; only called after {@link #contains} first returns false,
     * so a resource created after the cache was last populated is not wrongly reported as unknown. */
    boolean refreshContains(UUID resourceId);
}
