package com.meterly.tenant;

import com.meterly.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    public record Session(Map<String,Object> body,String refreshToken) {}
    private final TenantStore db;
    private final PasswordEncoder passwords;
    private final TokenIssuer issuer;
    private final Clock clock;
    private final String dummyHash;
    public AuthService(TenantStore db,PasswordEncoder passwords,TokenIssuer issuer,Clock clock) {
        this.db=db; this.passwords=passwords; this.issuer=issuer; this.clock=clock;
        dummyHash=passwords.encode(Secrets.token());
    }
    public Session login(String email,String password) {
        Map<String,Object> found=db.optional("select * from auth_find_user_by_email(?)",Secrets.email(email)).orElse(null);
        String hash=found==null?dummyHash:(String)found.get("password_hash");
        boolean valid=password.getBytes(StandardCharsets.UTF_8).length<=72 && passwords.matches(password,hash);
        if(!valid || found==null || !"ACTIVE".equals(found.get("user_status")) || !"ACTIVE".equals(found.get("tenant_status")))
            throw invalidCredentials();
        return db.inTenant(TenantStore.uuid(found,"tenant_id"),()-> {
            db.lockTenant();
            Map<String,Object> user=activeUser(TenantStore.uuid(found,"user_id"));
            if(user==null) throw invalidCredentials();
            return createSession(user,UUID.randomUUID(),null);
        });
    }
    public Session refresh(String token) {
        Map<String,Object> found=findRefresh(token);
        if(found==null) throw invalidRefresh();
        Session session=db.inTenant(TenantStore.uuid(found,"tenant_id"),()-> {
            // Serializes every exchange in a tenant. A losing concurrent exchange observes revocation.
            db.lockTenant();
            Map<String,Object> current=db.required("select * from refresh_token where id=? for update",
                    "INVALID_REFRESH_TOKEN",found.get("token_id"));
            UUID family=TenantStore.uuid(current,"family_id");
            if(current.get("revoked_at")!=null) {
                revokeFamily(family);
                return null; // Commit family revocation before returning 401 outside this transaction.
            }
            Map<String,Object> user=activeUser(TenantStore.uuid(current,"user_id"));
            if(!((Instant)current.get("expires_at")).isAfter(clock.instant()) || user==null) {
                revokeFamily(family); return null;
            }
            return createSession(user,family,TenantStore.uuid(current,"id"));
        });
        if(session==null) throw invalidRefresh();
        return session;
    }
    public void logout(String token) {
        Map<String,Object> found=findRefresh(token);
        if(found!=null) db.inTenant(TenantStore.uuid(found,"tenant_id"),()-> {
            db.lockTenant(); revokeFamily(TenantStore.uuid(found,"family_id")); return null;
        });
    }
    private Map<String,Object> findRefresh(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        return db.optional("select * from auth_find_refresh_token(?)",Secrets.hash(token)).orElse(null);
    }
    private void revokeFamily(UUID family) {
        db.update("update refresh_token set revoked_at=coalesce(revoked_at,now()) where family_id=?",family);
    }
    private Map<String,Object> activeUser(UUID id) {
        return db.optional("""
                select u.id,u.email,u.role,u.tenant_id,t.name as tenant_name
                from app_user u join tenant t on t.id=u.tenant_id
                where u.id=? and u.status='ACTIVE' and t.status='ACTIVE'
                """,id).orElse(null);
    }
    private Session createSession(Map<String,Object> user,UUID family,UUID old) {
        String token=Secrets.token();
        UUID id=UUID.randomUUID();
        db.update("""
                insert into refresh_token(id,tenant_id,user_id,token_hash,family_id,expires_at)
                values (?,?,?,?,?,?)
                """,id,user.get("tenant_id"),user.get("id"),Secrets.hash(token),family,
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(7))));
        if(old!=null) db.update("update refresh_token set revoked_at=now(),replaced_by=? where id=?",id,old);
        return new Session(Map.of("access_token",issuer.issue(user),"token_type","Bearer","expires_in",900,"user",user),token);
    }
    private static ApiException invalidCredentials() { return ApiException.unauthorized("INVALID_CREDENTIALS","Invalid email or password"); }
    private static ApiException invalidRefresh() { return ApiException.unauthorized("INVALID_REFRESH_TOKEN","Refresh token is invalid or expired"); }
}
