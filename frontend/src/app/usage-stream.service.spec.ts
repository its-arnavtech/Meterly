import { UsageStreamService } from './usage-stream.service';

describe('UsageStreamService', () => {
  it('applies a STOMP payload to the live usage signal', () => {
    const service = new UsageStreamService();

    service.applyMessage({body: JSON.stringify({
      tenantId: '11111111-1111-1111-1111-111111111111',
      resourceId: '55555555-5555-5555-5555-555555555555',
      totalUnits: 950,
      publishedAt: new Date().toISOString()
    })});

    expect(service.totalUnits()).toBe(950);
  });
});
