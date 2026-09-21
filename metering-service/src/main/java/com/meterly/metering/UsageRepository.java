package com.meterly.metering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.NotificationEvent;
import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.common.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class UsageRepository {
    record Resource(UUID id, String name, String unitLabel) {}
    record Plan(String code, String name, long includedUnits) {}
    record Snapshot(long units, long version) {}
    record Applied(Snapshot snapshot, boolean inserted) {}
    record Point(Instant bucketStart, long units) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    UsageRepository(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) { this.jdbc=jdbc; this.mapper=mapper; this.clock=clock; }
    List<Resource> resources(boolean activeOnly) {
        return jdbc.query("SELECT id,name,unit_label FROM metered_resource WHERE tenant_id=? " + (activeOnly ? "AND status='ACTIVE' " : "")+"ORDER BY name", (rs,n)->new Resource(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),TenantContext.require());
    }
    Plan plan(BillingPeriod period) {
        var plans=jdbc.query("""
                SELECT p.code,p.name,p.included_units FROM subscription s JOIN plan p ON p.id=s.plan_id
                WHERE s.tenant_id=? AND s.status='ACTIVE' AND s.start_date < ? AND (s.end_date IS NULL OR s.end_date > ?)
                ORDER BY s.start_date DESC LIMIT 1
                """,(rs,n)->new Plan(rs.getString(1),rs.getString(2),rs.getLong(3)), TenantContext.require(),period.end(),period.start());
        return plans.isEmpty() ? new Plan("NONE","No active plan",0) : plans.getFirst();
    }
    void lock(UUID resource, BillingPeriod period) {
        // Held until commit: counter versions follow committed order, including manual reconciliation.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,TenantContext.require()+":"+resource+":"+period.start());
    }
    Applied insert(UsageEventMessage event, BillingPeriod period) {
        lock(event.resourceId(),period);
        var ids=jdbc.query("""
            INSERT INTO usage_event(tenant_id,resource_id,idempotency_key,quantity,occurred_at,received_at)
            VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,idempotency_key) DO NOTHING RETURNING id
            """,(rs,n)->rs.getLong(1),event.tenantId(),event.resourceId(),event.idempotencyKey(),event.quantity(),Timestamp.from(event.occurredAt()),Timestamp.from(event.receivedAt()));
        if (ids.isEmpty()) return new Applied(snapshot(event.resourceId(),period),false);
        Snapshot state=jdbc.queryForObject("""
            INSERT INTO usage_counter_state(tenant_id,resource_id,period_start,total_units,ledger_version)
            VALUES(?,?,?,?,?) ON CONFLICT(tenant_id,resource_id,period_start)
            DO UPDATE SET total_units=usage_counter_state.total_units+EXCLUDED.total_units,ledger_version=EXCLUDED.ledger_version
            RETURNING total_units,ledger_version
            """,(rs,n)->new Snapshot(rs.getLong(1),rs.getLong(2)),event.tenantId(),event.resourceId(),period.start(),event.quantity(),ids.getFirst());
        thresholds(event.resourceId(),period,state.units());
        return new Applied(state,true);
    }
    Snapshot snapshot(UUID resource, BillingPeriod period) {
        return jdbc.queryForObject("""
            SELECT COALESCE(sum(quantity),0),COALESCE(max(id),0) FROM usage_event
            WHERE tenant_id=? AND resource_id=? AND occurred_at>=? AND occurred_at<?
            """,(rs,n)->new Snapshot(rs.getLong(1),rs.getLong(2)),TenantContext.require(),resource,Timestamp.from(period.startInstant()),Timestamp.from(period.endInstant()));
    }
    Snapshot reconcile(UUID resource,BillingPeriod period) {
        lock(resource,period); Snapshot state=snapshot(resource,period);
        jdbc.update("""
            INSERT INTO usage_counter_state(tenant_id,resource_id,period_start,total_units,ledger_version) VALUES(?,?,?,?,?)
            ON CONFLICT(tenant_id,resource_id,period_start) DO UPDATE SET total_units=EXCLUDED.total_units,ledger_version=EXCLUDED.ledger_version
            """,TenantContext.require(),resource,period.start(),state.units(),state.version());
        thresholds(resource,period,state.units());
        return state;
    }
    List<Point> timeseries(UUID resource,String granularity,Instant from,Instant to) {
        return jdbc.query("""
            SELECT date_trunc(?,occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS bucket, SUM(quantity)
            FROM usage_event WHERE tenant_id=? AND resource_id=? AND occurred_at>=? AND occurred_at<?
            GROUP BY bucket ORDER BY bucket
            """,(rs,n)->new Point(rs.getTimestamp(1).toInstant(),rs.getLong(2)),granularity,TenantContext.require(),resource,Timestamp.from(from),Timestamp.from(to));
    }
    List<UUID> activeTenants() { return jdbc.query("SELECT * FROM system_active_tenant_ids()",(rs,n)->rs.getObject(1,UUID.class)); }
    private void thresholds(UUID resource,BillingPeriod period,long units) {
        if (!period.equals(BillingPeriod.current(clock))) return;
        Plan plan=plan(period); if (plan.includedUnits()<=0) return;
        for (int percent:new int[]{80,100}) {
            if (java.math.BigInteger.valueOf(units).multiply(java.math.BigInteger.valueOf(100)).compareTo(java.math.BigInteger.valueOf(plan.includedUnits()).multiply(java.math.BigInteger.valueOf(percent)))<0) continue;
            UUID id=UUID.randomUUID();
            int inserted=jdbc.update("INSERT INTO usage_threshold(tenant_id,resource_id,period_start,threshold_percent,event_id) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",TenantContext.require(),resource,period.start(),percent,id);
            if (inserted==0) continue;
            String name=jdbc.queryForObject("SELECT name FROM metered_resource WHERE tenant_id=? AND id=?",String.class,TenantContext.require(),resource);
            var event=new NotificationEvent.UsageThresholdCrossed(id,TenantContext.require(),clock.instant(),resource,name,period.start(),percent,units,plan.includedUnits());
            try {
                jdbc.update("INSERT INTO outbox_event(id,tenant_id,topic,event_key,event_type,payload) VALUES(?,?,?,?,?,?::jsonb)",id,TenantContext.require(),Topics.NOTIFICATION_EVENTS,TenantContext.require().toString(),NotificationEvent.USAGE_THRESHOLD_CROSSED,mapper.writeValueAsString(event));
            } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize threshold event",e); }
        }
    }
}
