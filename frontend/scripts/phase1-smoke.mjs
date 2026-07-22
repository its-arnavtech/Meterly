import {Client} from '@stomp/stompjs';
import crypto from 'node:crypto';

const urls = {tenant: 'http://127.0.0.1:8080', ingestion: 'http://127.0.0.1:8181', metering: 'http://127.0.0.1:8082', billing: 'http://127.0.0.1:8083'};
const now = new Date();
const periodStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)).toISOString().slice(0, 10);
const periodEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1)).toISOString().slice(0, 10);
const sharedIdempotencyKey = crypto.randomUUID();
const cases = [
  {slug: 'developer', planId: '22222222-2222-2222-2222-222222222222', plan: 'Developer', quantity: 1375, expected: 56.50},
  {slug: 'growth', planId: '22222222-2222-2222-2222-222222222223', plan: 'Growth', quantity: 12000, expected: 194.00},
  {slug: 'scale', planId: '22222222-2222-2222-2222-222222222224', plan: 'Scale', quantity: 55000, expected: 514.00}
];

async function call(base, path, {method = 'GET', token, apiKey, body, expected = 200} = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (apiKey) headers['X-Api-Key'] = apiKey;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(base + path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (response.status !== expected) throw new Error(`${method} ${path}: expected ${expected}, received ${response.status}: ${text}`);
  return payload;
}

function assert(condition, message) { if (!condition) throw new Error(message); }

async function subscribe(token, tenantId, expectedTotal) {
  let messages = 0;
  let resolveReady, rejectReady, resolveUpdate, rejectUpdate;
  const ready = new Promise((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
  const update = new Promise((resolve, reject) => { resolveUpdate = resolve; rejectUpdate = reject; });
  const client = new Client({
    brokerURL: 'ws://127.0.0.1:8082/ws/usage', reconnectDelay: 0,
    connectHeaders: {Authorization: `Bearer ${token}`},
    onConnect: () => {
      client.subscribe(`/topic/usage/${tenantId}`, frame => {
        const body = JSON.parse(frame.body); messages += 1;
        if (body.totalUnits === expectedTotal) resolveUpdate({body, receivedAt: performance.now()});
      });
      resolveReady();
    },
    onStompError: frame => { const error = new Error(frame.headers.message ?? 'STOMP error'); rejectReady(error); rejectUpdate(error); },
    onWebSocketError: event => { const error = new Error(`WebSocket error: ${event.type}`); rejectReady(error); rejectUpdate(error); }
  });
  client.activate();
  await Promise.race([ready, new Promise((_, reject) => setTimeout(() => reject(new Error('STOMP connect timeout')), 5000))]);
  return {client, messages: () => messages, update};
}

const results = [];
for (const item of cases) {
  const email = `phase1-${item.slug}@meterly.test`;
  const password = `phase1-${item.slug}-password`;
  const onboard = await call(urls.tenant, '/v1/tenants', {method: 'POST', expected: 201,
    body: {name: `Phase 1 ${item.plan}`, admin_email: email, admin_password: password, plan_id: item.planId}});
  const session = await call(urls.tenant, '/v1/auth/login', {method: 'POST', body: {email, password}});
  assert(session.tenantId === onboard.tenantId && session.role === 'TENANT_ADMIN', `${item.plan}: incorrect admin login`);
  const resources = await call(urls.tenant, '/v1/resources', {token: session.accessToken});
  assert(resources.length === 1 && resources[0].id === onboard.resourceId, `${item.plan}: resource isolation failed`);
  const plan = await call(urls.tenant, '/v1/plans/current', {token: session.accessToken});
  assert(plan.name === item.plan, `${item.plan}: assigned plan mismatch`);

  const userEmail = `phase1-${item.slug}-user@meterly.test`;
  await call(urls.tenant, '/v1/users', {method: 'POST', expected: 201, token: session.accessToken,
    body: {email: userEmail, password: `${password}-user`, role: 'TENANT_USER'}});
  const user = await call(urls.tenant, '/v1/auth/login', {method: 'POST', body: {email: userEmail, password: `${password}-user`}});
  await call(urls.tenant, '/v1/plans/current', {token: user.accessToken, expected: 403});
  await call(urls.billing, '/v1/billing/run', {method: 'POST', token: user.accessToken, expected: 403, body: {period_start: periodStart, period_end: periodEnd}});

  const stream = await subscribe(session.accessToken, onboard.tenantId, item.quantity);
  const startedAt = performance.now();
  const event = {idempotency_key: sharedIdempotencyKey, resource_id: onboard.resourceId, quantity: item.quantity, occurred_at: new Date().toISOString()};
  await call(urls.ingestion, '/v1/usage-events', {method: 'POST', expected: 202, apiKey: onboard.apiKey, body: event});
  await call(urls.ingestion, '/v1/usage-events', {method: 'POST', expected: 202, apiKey: onboard.apiKey, body: event});
  const live = await Promise.race([stream.update, new Promise((_, reject) => setTimeout(() => reject(new Error(`${item.plan}: live update timeout`)), 5000))]);
  const latencyMs = Math.round(live.receivedAt - startedAt);
  await new Promise(resolve => setTimeout(resolve, 500));
  await stream.client.deactivate();
  assert(stream.messages() === 1, `${item.plan}: duplicate delivery broadcast ${stream.messages()} times`);
  assert(latencyMs < 2000, `${item.plan}: live latency ${latencyMs}ms exceeded 2000ms`);
  const usage = await call(urls.metering, `/v1/usage/current?resource_id=${onboard.resourceId}`, {token: session.accessToken});
  assert(usage.total_units === item.quantity, `${item.plan}: expected ${item.quantity} units, got ${usage.total_units}`);

  const invoice = await call(urls.billing, '/v1/billing/run', {method: 'POST', token: session.accessToken, body: {period_start: periodStart, period_end: periodEnd}});
  const rerun = await call(urls.billing, '/v1/billing/run', {method: 'POST', token: session.accessToken, body: {period_start: periodStart, period_end: periodEnd}});
  assert(invoice.id === rerun.id, `${item.plan}: invoice rerun was not idempotent`);
  assert(Number(invoice.totalAmount) === item.expected, `${item.plan}: expected $${item.expected.toFixed(2)}, got $${invoice.totalAmount}`);
  const list = await call(urls.billing, '/v1/invoices', {token: session.accessToken});
  const read = await call(urls.billing, `/v1/invoices/${invoice.id}`, {token: session.accessToken});
  assert(list.length === 1 && read.id === invoice.id && Number(read.totalAmount) === item.expected, `${item.plan}: invoice list/read mismatch`);
  results.push({plan: item.plan, tenantId: onboard.tenantId, resourceId: onboard.resourceId, usage: usage.total_units,
    invoiceId: invoice.id, total: Number(invoice.totalAmount).toFixed(2), lineItems: invoice.lineItems.length, latencyMs,
    duplicateBroadcasts: stream.messages(), adminOnlyStatus: 403, token: session.accessToken});
}

for (let index = 0; index < results.length; index += 1) {
  const other = results[(index + 1) % results.length];
  await call(urls.billing, `/v1/invoices/${other.invoiceId}`, {token: results[index].token, expected: 404});
  const own = await call(urls.billing, '/v1/invoices', {token: results[index].token});
  assert(own.length === 1 && own[0].tenantId === results[index].tenantId, `${results[index].plan}: cross-tenant invoice visibility`);
}

console.log(JSON.stringify({status: 'PASS', sharedIdempotencyKey, tenants: results.map(({token, ...result}) => result)}, null, 2));
