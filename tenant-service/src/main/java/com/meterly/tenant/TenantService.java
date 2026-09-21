package com.meterly.tenant;

import com.meterly.common.security.CurrentUser;
import com.meterly.common.tenant.TenantContext;
import com.meterly.common.web.ApiException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import static com.meterly.tenant.TenantRequests.*;

@Service
public class TenantService {
    private static final String USER_COLUMNS="id,email,role,status,created_at";
    private static final String RESOURCE_COLUMNS="id,name,unit_label,status,created_at";
    private static final String KEY_COLUMNS="id,name,prefix,created_at,last_used_at,revoked_at";
    private final TenantStore db;
    private final PasswordEncoder passwords;
    private final Clock clock;
    public TenantService(TenantStore db,PasswordEncoder passwords,Clock clock) {
        this.db=db; this.passwords=passwords; this.clock=clock;
    }
    public Map<String,Object> signup(Signup input) {
        Secrets.validatePassword(input.adminPassword());
        String name=input.tenantName().trim();
        if(name.length()<2) throw ApiException.badRequest("VALIDATION_FAILED","Tenant name is too short");
        String hash=passwords.encode(input.adminPassword());
        UUID tenant=UUID.randomUUID(); UUID user=UUID.randomUUID(); UUID resource=UUID.randomUUID();
        try {
            return db.inTenant(tenant,()-> {
                Map<String,Object> plan=activePlan(input.planCode()==null?"STARTER":input.planCode());
                db.update("insert into tenant(id,name) values(?,?)",tenant,name);
                db.update("insert into app_user(id,tenant_id,email,role,password_hash) values(?,?,?,'TENANT_ADMIN',?)",
                        user,tenant,Secrets.email(input.adminEmail()),hash);
                db.update("insert into subscription(tenant_id,plan_id,start_date) values(?,?,?)",tenant,plan.get("id"),today());
                db.update("insert into metered_resource(id,tenant_id,name,unit_label) values(?,?,'API calls','call')",resource,tenant);
                return Map.of("tenant",tenant(),"admin",user(user),"subscription",subscriptions().get("current"),
                        "resources",resources());
            });
        } catch(DuplicateKeyException ex) { throw ApiException.conflict("EMAIL_TAKEN","Email is already registered"); }
    }
    public List<Map<String,Object>> plans() {
        return db.rows("select id,code,name,base_price,included_units,currency from plan where active order by sort_order,code")
                .stream().map(this::withTiers).toList();
    }
    public Map<String,Object> me(CurrentUser user) { return as(user,false,()->Map.of("user",user(user.userId()),"tenant",tenant())); }
    public Map<String,Object> getTenant(CurrentUser user) { return as(user,false,this::tenant); }
    public Map<String,Object> updateTenant(CurrentUser user,TenantPatch patch) {
        String name=patch.name().trim();
        if(name.length()<2) throw ApiException.badRequest("VALIDATION_FAILED","Tenant name is too short");
        return as(user,true,()-> { db.update("update tenant set name=? where id=?",name,user.tenantId()); return tenant(); });
    }
    public Map<String,Object> getSubscription(CurrentUser user) { return as(user,true,this::subscriptions); }
    public Map<String,Object> changePlan(CurrentUser user,PlanChange input) {
        return as(user,true,()-> {
            db.lockTenant();
            Map<String,Object> target=activePlan(input.planCode());
            Map<String,Object> current=currentSubscription();
            if(current==null) throw ApiException.unprocessable("NO_SUBSCRIPTION","There is no active subscription");
            LocalDate boundary=today().withDayOfMonth(1).plusMonths(1);
            db.update("update subscription set status='CANCELED' where start_date>=? and status='ACTIVE'",boundary);
            if(current.get("plan_id").equals(target.get("id"))) {
                db.update("update subscription set end_date=null where id=?",current.get("id"));
            } else {
                db.update("update subscription set end_date=? where id=?",boundary,current.get("id"));
                db.update("insert into subscription(tenant_id,plan_id,start_date) values(?,?,?)",user.tenantId(),target.get("id"),boundary);
            }
            return subscriptions();
        });
    }
    public List<Map<String,Object>> getResources(CurrentUser user) { return as(user,false,this::resources); }
    public Map<String,Object> createResource(CurrentUser user,ResourceCreate input) {
        try {
            return as(user,true,()-> {
                UUID id=UUID.randomUUID();
                db.update("insert into metered_resource(id,tenant_id,name,unit_label) values(?,?,?,?)",
                        id,user.tenantId(),input.name().trim(),input.unitLabel().trim());
                return resource(id);
            });
        } catch(DuplicateKeyException ex) { throw ApiException.conflict("RESOURCE_NAME_TAKEN","Resource name already exists"); }
    }
    public Map<String,Object> updateResource(CurrentUser user,UUID id,ResourcePatch input) {
        try {
            return as(user,true,()-> {
                resource(id);
                db.update("update metered_resource set name=coalesce(?,name),unit_label=coalesce(?,unit_label),status=coalesce(?,status) where id=?",
                        trim(input.name()),trim(input.unitLabel()),input.status(),id);
                return resource(id);
            });
        } catch(DuplicateKeyException ex) { throw ApiException.conflict("RESOURCE_NAME_TAKEN","Resource name already exists"); }
    }
    public List<Map<String,Object>> getUsers(CurrentUser user) { return as(user,true,()->db.rows("select "+USER_COLUMNS+" from app_user order by created_at,id")); }
    public Map<String,Object> createUser(CurrentUser caller,UserCreate input) {
        Secrets.validatePassword(input.password());
        String hash=passwords.encode(input.password());
        try {
            return as(caller,true,()-> {
                UUID id=UUID.randomUUID();
                db.update("insert into app_user(id,tenant_id,email,role,password_hash) values(?,?,?,?,?)",
                        id,caller.tenantId(),Secrets.email(input.email()),input.role(),hash);
                return user(id);
            });
        } catch(DuplicateKeyException ex) { throw ApiException.conflict("EMAIL_TAKEN","Email is already registered"); }
    }
    public Map<String,Object> updateUser(CurrentUser caller,UUID id,UserPatch input) {
        return as(caller,true,()-> {
            db.lockTenant(); // Serialize competing admin changes, including the last-admin check.
            Map<String,Object> target=user(id);
            String role=input.role()==null?(String)target.get("role"):input.role();
            String status=input.status()==null?(String)target.get("status"):input.status();
            boolean removingAdmin=!"TENANT_ADMIN".equals(role) || !"ACTIVE".equals(status);
            if(id.equals(caller.userId()) && removingAdmin)
                throw ApiException.unprocessable("CANNOT_MODIFY_SELF","You cannot disable or demote yourself");
            if(removingAdmin && "TENANT_ADMIN".equals(target.get("role")) && "ACTIVE".equals(target.get("status"))
                    && db.rows("select id from app_user where role='TENANT_ADMIN' and status='ACTIVE'").size()<=1)
                throw ApiException.unprocessable("LAST_ADMIN","At least one active admin is required");
            db.update("update app_user set role=?,status=? where id=?",role,status,id);
            if(!role.equals(target.get("role")) || !status.equals(target.get("status")))
                db.update("update refresh_token set revoked_at=coalesce(revoked_at,now()) where user_id=?",id);
            return user(id);
        });
    }
    public List<Map<String,Object>> getKeys(CurrentUser user) { return as(user,true,()->db.rows("select "+KEY_COLUMNS+" from api_key order by created_at desc,id")); }
    public Map<String,Object> createKey(CurrentUser user,KeyCreate input) {
        return as(user,true,()-> {
            UUID id=UUID.randomUUID(); String raw="mk_live_"+Secrets.token();
            db.update("insert into api_key(id,tenant_id,name,prefix,key_hash,created_by) values(?,?,?,?,?,?)",
                    id,user.tenantId(),input.name().trim(),raw.substring(0,12),Secrets.hash(raw),user.userId());
            Map<String,Object> result=key(id); result.put("key",raw); return result;
        });
    }
    public void revokeKey(CurrentUser user,UUID id) {
        as(user,true,()-> { key(id); db.update("update api_key set revoked_at=coalesce(revoked_at,now()) where id=?",id); return null; });
    }
    public Map<String,Object> verifyKey(String raw) {
        if(!raw.matches("mk_live_[A-Za-z0-9_-]{43}")) throw invalidKey();
        Map<String,Object> found=db.optional("select * from auth_find_api_key(?)",Secrets.hash(raw)).orElseThrow(TenantService::invalidKey);
        if(Boolean.TRUE.equals(found.get("revoked")) || !"ACTIVE".equals(found.get("tenant_status"))) throw invalidKey();
        UUID tenant=TenantStore.uuid(found,"tenant_id"); UUID id=TenantStore.uuid(found,"api_key_id");
        return db.inTenant(tenant,()-> {
            if(db.optional("select k.id from api_key k join tenant t on t.id=k.tenant_id where k.id=? and k.revoked_at is null and t.status='ACTIVE'",id).isEmpty())
                throw invalidKey();
            db.update("update api_key set last_used_at=now() where id=? and (last_used_at is null or last_used_at<now()-interval '1 minute')",id);
            return Map.of("tenant_id",tenant,"api_key_id",id,"resource_ids",activeResources());
        });
    }
    public Map<String,Object> internalResources(UUID tenant) {
        return db.inTenant(tenant,()-> { activeTenant(); return Map.of("tenant_id",tenant,"resource_ids",activeResources()); });
    }
    public List<Map<String,Object>> internalAdmins(UUID tenant) {
        return db.inTenant(tenant,()-> { activeTenant(); return db.rows("select email from app_user where status='ACTIVE' and role='TENANT_ADMIN' order by email"); });
    }
    private <T> T as(CurrentUser user,boolean admin,Supplier<T> work) {
        return db.inTenant(user.tenantId(),()-> {
            if(admin) db.lockTenant();
            Map<String,Object> active=db.optional("select u.role from app_user u join tenant t on t.id=u.tenant_id where u.id=? and u.status='ACTIVE' and t.status='ACTIVE'",user.userId())
                    .orElseThrow(()->ApiException.unauthorized("UNAUTHENTICATED","Account is unavailable"));
            if(admin && !"TENANT_ADMIN".equals(active.get("role"))) throw ApiException.forbidden("FORBIDDEN","Admin access is required");
            return work.get();
        });
    }
    private Map<String,Object> tenant() { return db.required("select id,name,status,created_at from tenant where id=?","TENANT_NOT_FOUND",TenantContext.require()); }
    private void activeTenant() { db.required("select id from tenant where id=? and status='ACTIVE'","TENANT_NOT_FOUND",TenantContext.require()); }
    private Map<String,Object> user(UUID id) { return db.required("select "+USER_COLUMNS+" from app_user where id=?","USER_NOT_FOUND",id); }
    private Map<String,Object> resource(UUID id) { return db.required("select "+RESOURCE_COLUMNS+" from metered_resource where id=?","RESOURCE_NOT_FOUND",id); }
    private Map<String,Object> key(UUID id) { return db.required("select "+KEY_COLUMNS+" from api_key where id=?","API_KEY_NOT_FOUND",id); }
    private List<Map<String,Object>> resources() { return db.rows("select "+RESOURCE_COLUMNS+" from metered_resource order by created_at,id"); }
    private List<Object> activeResources() { return db.rows("select id from metered_resource where status='ACTIVE' order by id").stream().map(r->r.get("id")).toList(); }
    private Map<String,Object> activePlan(String code) {
        return db.optional("select id,code,name,base_price,included_units,currency from plan where code=? and active",code)
                .orElseThrow(()->ApiException.unprocessable("UNKNOWN_PLAN","Plan is unavailable"));
    }
    private Map<String,Object> withTiers(Map<String,Object> plan) {
        plan.put("tiers",db.rows("select from_unit,to_unit,unit_price from pricing_tier where plan_id=? order by from_unit",plan.get("id")));
        return plan;
    }
    private Map<String,Object> currentSubscription() {
        return db.optional("select id,plan_id,start_date,end_date,status from subscription where status='ACTIVE' and start_date<=? and (end_date is null or end_date>?)",today(),today()).orElse(null);
    }
    private Map<String,Object> subscriptions() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("current",subscriptionView(currentSubscription()));
        result.put("scheduled",subscriptionView(db.optional("select id,plan_id,start_date,end_date,status from subscription where status='ACTIVE' and start_date>? order by start_date limit 1",today()).orElse(null)));
        return result;
    }
    private Map<String,Object> subscriptionView(Map<String,Object> subscription) {
        if(subscription==null) return null;
        Object id=subscription.remove("plan_id");
        subscription.put("plan",withTiers(db.required("select id,code,name,base_price,included_units,currency from plan where id=?","UNKNOWN_PLAN",id)));
        return subscription;
    }
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneOffset.UTC)); }
    private static String trim(String value) { return value==null?null:value.trim(); }
    private static ApiException invalidKey() { return ApiException.unauthorized("INVALID_API_KEY","API key is invalid"); }
}
