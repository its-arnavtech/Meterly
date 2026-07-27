import crypto from 'node:crypto';

const urls = {
  tenant: 'http://127.0.0.1:8080',
  ingestion: 'http://127.0.0.1:8181',
  metering: 'http://127.0.0.1:8082',
  billing: 'http://127.0.0.1:8083',
  prometheus: 'http://127.0.0.1:9091',
  grafana: 'http://127.0.0.1:3000'
};

const now = new Date();
const periodStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1))
  .toISOString().slice(0, 10);
const periodEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1))
  .toISOString().slice(0, 10);

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function request(base, path, {method = 'GET', token, apiKey, body, expected = 200} = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (apiKey) headers['X-Api-Key'] = apiKey;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(base + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  if (response.status !== expected) {
    throw new Error(`${method} ${path}: expected ${expected}, received ${response.status}: ${text}`);
  }
  return text ? JSON.parse(text) : null;
}

function jwtPart(token, index) {
  return JSON.parse(Buffer.from(token.split('.')[index], 'base64url').toString('utf8'));
}

async function waitFor(description, probe, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await probe();
      if (value !== false && value !== null && value !== undefined) return value;
    } catch (error) {
      lastError = error;
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error(`${description} did not become true${lastError ? `: ${lastError.message}` : ''}`);
}

async function prometheusValue(expression) {
  const payload = await request(
    urls.prometheus,
    `/api/v1/query?query=${encodeURIComponent(expression)}`
  );
  if (payload.status !== 'success' || payload.data.result.length === 0) return null;
  return Number(payload.data.result[0].value[1]);
}

const suffix = crypto.randomUUID().slice(0, 8);
const adminEmail = `phase3-admin-${suffix}@meterly.test`;
const adminPassword = `phase3-admin-${suffix}-password`;
const userEmail = `phase3-user-${suffix}@meterly.test`;
const userPassword = `phase3-user-${suffix}-password`;

const onboard = await request(urls.tenant, '/v1/tenants', {
  method: 'POST',
  expected: 201,
  body: {
    name: `Phase 3 ${suffix}`,
    admin_email: adminEmail,
    admin_password: adminPassword,
    plan_id: '22222222-2222-2222-2222-222222222222'
  }
});
const admin = await request(urls.tenant, '/v1/auth/login', {
  method: 'POST',
  body: {email: adminEmail, password: adminPassword}
});
const header = jwtPart(admin.accessToken, 0);
const claims = jwtPart(admin.accessToken, 1);
assert(header.alg === 'RS256', `expected Keycloak RS256 token, received ${header.alg}`);
assert(claims.iss?.endsWith('/realms/meterly'), `unexpected Keycloak issuer ${claims.iss}`);
assert(claims.tenant_id === onboard.tenantId, 'Keycloak tenant_id claim mismatch');
assert(claims.role === 'TENANT_ADMIN', 'Keycloak admin role claim mismatch');

await request(urls.tenant, '/v1/users', {
  method: 'POST',
  expected: 201,
  token: admin.accessToken,
  body: {email: userEmail, password: userPassword, role: 'TENANT_USER'}
});
const user = await request(urls.tenant, '/v1/auth/login', {
  method: 'POST',
  body: {email: userEmail, password: userPassword}
});
const userClaims = jwtPart(user.accessToken, 1);
assert(userClaims.tenant_id === onboard.tenantId, 'Keycloak user tenant_id claim mismatch');
assert(userClaims.role === 'TENANT_USER', 'Keycloak user role claim mismatch');
await request(urls.billing, '/v1/billing/run', {
  method: 'POST',
  token: user.accessToken,
  expected: 403,
  body: {period_start: periodStart, period_end: periodEnd}
});
await request(urls.billing, '/v1/invoices', {
  token: 'not-a-valid-jwt',
  expected: 401
});

const idempotencyKey = crypto.randomUUID();
const event = {
  resource_id: onboard.resourceId,
  idempotency_key: idempotencyKey,
  quantity: 1375,
  occurred_at: new Date().toISOString()
};
await request(urls.ingestion, '/v1/usage-events', {
  method: 'POST',
  expected: 202,
  apiKey: onboard.apiKey,
  body: event
});
await request(urls.ingestion, '/v1/usage-events', {
  method: 'POST',
  expected: 202,
  apiKey: onboard.apiKey,
  body: event
});
await waitFor('idempotent metered total', async () => {
  const usage = await request(
    urls.metering,
    `/v1/usage/current?resource_id=${onboard.resourceId}`,
    {token: admin.accessToken}
  );
  return usage.total_units === 1375 ? usage : false;
});
const invoice = await request(urls.billing, '/v1/billing/run', {
  method: 'POST',
  token: admin.accessToken,
  body: {period_start: periodStart, period_end: periodEnd}
});
assert(Number(invoice.totalAmount) === 56.5, `expected $56.50, received ${invoice.totalAmount}`);

const targets = await waitFor('five healthy Prometheus service targets', async () => {
  const payload = await request(urls.prometheus, '/api/v1/targets');
  const meterlyTargets = payload.data.activeTargets.filter(
    target => target.labels.job === 'meterly'
  );
  return meterlyTargets.length === 5 && meterlyTargets.every(target => target.health === 'up')
    ? meterlyTargets
    : false;
}, 60000);

const notificationCount = await waitFor('both stub notifications', async () => {
  const value = await prometheusValue(
    `sum(meterly_notification_delivered_total{tenant_id="${onboard.tenantId}"})`
  );
  return value >= 2 ? value : false;
}, 60000);
const ingestionCount = await waitFor('ingestion metric', () =>
  prometheusValue(`meterly_usage_ingestion_total{tenant_id="${onboard.tenantId}"}`));
const invoiceCount = await waitFor('invoice metric', () =>
  prometheusValue(`meterly_invoice_generated_total{tenant_id="${onboard.tenantId}"}`));
const authRejections = await waitFor('authentication rejection metric', () =>
  prometheusValue('sum(meterly_auth_rejections_total)'));

const dashboards = await request(urls.grafana, '/api/search?query=Meterly%20Overview');
assert(
  dashboards.some(dashboard => dashboard.uid === 'meterly-overview'),
  'provisioned Meterly Grafana dashboard was not found'
);

console.log(JSON.stringify({
  status: 'PASS',
  keycloak: {
    algorithm: header.alg,
    issuer: claims.iss,
    tenantId: onboard.tenantId,
    adminRole: claims.role,
    userRole: userClaims.role,
    userBillingStatus: 403
  },
  usage: {
    idempotencyKey,
    total: 1375,
    invoiceId: invoice.id,
    invoiceTotal: Number(invoice.totalAmount).toFixed(2)
  },
  observability: {
    healthyTargets: targets.length,
    ingestionCount,
    invoiceCount,
    authRejections,
    dashboardUid: 'meterly-overview'
  },
  notifications: {
    delivered: notificationCount,
    expectedMinimum: 2
  }
}, null, 2));
