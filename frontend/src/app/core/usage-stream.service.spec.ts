import {UsageStreamService} from './usage-stream.service';
describe('UsageStreamService', () => {
  it('applies a STOMP payload to the live usage signal', () => {
    const service = new UsageStreamService();
    service.applyMessage({body: JSON.stringify({tenantId: 'tenant', resourceId: 'resource', totalUnits: 950, updatedAt: new Date().toISOString()})});
    expect(service.totalUnits()).toBe(950);
  });
});
