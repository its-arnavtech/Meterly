package com.meterly.tenant.resource;

import com.meterly.common.security.CurrentUser;
import com.meterly.common.web.ApiException;
import com.meterly.tenant.security.CallerVerifier;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Metered resources of the caller's tenant. RLS makes other tenants' resources invisible (404). */
@Service
public class ResourceService {

    private final ResourceRepository resources;
    private final CallerVerifier callers;

    public ResourceService(ResourceRepository resources, CallerVerifier callers) {
        this.resources = resources;
        this.callers = callers;
    }

    @Transactional(readOnly = true)
    public List<Resource> list(CurrentUser caller) {
        callers.requireActive(caller);
        return resources.list();
    }

    @Transactional
    public Resource create(CurrentUser caller, String name, String unitLabel) {
        callers.requireActiveAdmin(caller);
        UUID id = UUID.randomUUID();
        try {
            resources.insert(id, caller.tenantId(), name, unitLabel);
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
        return resources.find(id).orElseThrow();
    }

    /** Applies the non-null fields of a partial update. */
    @Transactional
    public Resource update(CurrentUser caller, UUID id, String name, String unitLabel, Resource.ResourceStatus status) {
        callers.requireActiveAdmin(caller);
        Resource existing = resources.find(id)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "Resource " + id + " not found"));
        String newName = name == null ? existing.name() : name;
        try {
            resources.update(id,
                    newName,
                    unitLabel == null ? existing.unitLabel() : unitLabel,
                    status == null ? existing.status() : status);
        } catch (DuplicateKeyException e) {
            throw nameTaken(newName);
        }
        return resources.find(id).orElseThrow();
    }

    private static ApiException nameTaken(String name) {
        return ApiException.conflict("RESOURCE_NAME_TAKEN", "A resource named '" + name + "' already exists");
    }
}
