import crypto from 'node:crypto';

const tenantUrl = 'http://127.0.0.1:8080';
const ingestionUrl = 'http://127.0.0.1:8181';
const developerPlan = '22222222-2222-2222-2222-222222222222';
const runId = crypto.randomUUID();

async function onboard(label) {
  const response = await fetch(`${tenantUrl}/v1/tenants`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({
      name: `Rate limit ${label}`,
      admin_email: `rate-${label}-${runId}@meterly.test`,
      admin_password: 'correct-horse-battery',
      plan_id: developerPlan
    })
  });
  if (response.status !== 201) throw new Error(`Onboarding ${label} returned ${response.status}: ${await response.text()}`);
  return response.json();
}

async function submit(tenant, index) {
  const response = await fetch(`${ingestionUrl}/v1/usage-events`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json', 'X-Api-Key': tenant.apiKey},
    body: JSON.stringify({
      resource_id: tenant.resourceId,
      idempotency_key: crypto.randomUUID(),
      quantity: 1
    })
  });
  return {index, status: response.status, retryAfter: response.headers.get('retry-after')};
}

const tenantA = await onboard('a');
const tenantB = await onboard('b');
const tenantAResults = await Promise.all(Array.from({length: 80}, (_, index) => submit(tenantA, index)));
const accepted = tenantAResults.filter(result => result.status === 202).length;
const rejected = tenantAResults.filter(result => result.status === 429);
const unexpected = tenantAResults.filter(result => ![202, 429].includes(result.status));
if (accepted === 0 || rejected.length === 0 || unexpected.length !== 0) {
  throw new Error(`Expected a mix of 202/429 for tenant A; accepted=${accepted} rejected=${rejected.length} unexpected=${JSON.stringify(unexpected)}`);
}
if (rejected.some(result => !result.retryAfter || Number(result.retryAfter) < 1)) {
  throw new Error(`Every 429 must include a positive Retry-After: ${JSON.stringify(rejected)}`);
}
const tenantBResult = await submit(tenantB, 0);
if (tenantBResult.status !== 202) {
  throw new Error(`Tenant B was incorrectly throttled: ${JSON.stringify(tenantBResult)}`);
}

console.log(JSON.stringify({
  status: 'PASS',
  tenantA: {tenantId: tenantA.tenantId, requests: tenantAResults.length, accepted, rejected: rejected.length,
    retryAfterValues: [...new Set(rejected.map(result => result.retryAfter))]},
  tenantB: {tenantId: tenantB.tenantId, status: tenantBResult.status}
}, null, 2));
