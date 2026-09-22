import { applyDeltaToBuckets, LIVE_WINDOW_MS, toLineChartSeries } from './live-series.util';

describe('applyDeltaToBuckets', () => {
  it('creates a new bucket for a delta with no existing bucket', () => {
    const result = applyDeltaToBuckets([], 10_000, 5, 20_000);
    expect(result).toEqual([{ bucketStart: 10_000, units: 5 }]);
  });

  it('accumulates deltas that fall in the same 10s bucket', () => {
    const first = applyDeltaToBuckets([], 10_000, 5, 20_000);
    const second = applyDeltaToBuckets(first, 14_999, 3, 20_000);
    expect(second).toEqual([{ bucketStart: 10_000, units: 8 }]);
  });

  it('keeps separate buckets 10s apart in order', () => {
    let buckets = applyDeltaToBuckets([], 10_000, 5, 30_000);
    buckets = applyDeltaToBuckets(buckets, 25_000, 2, 30_000);
    expect(buckets).toEqual([
      { bucketStart: 10_000, units: 5 },
      { bucketStart: 20_000, units: 2 }
    ]);
  });

  it('drops buckets older than the 15-minute rolling window', () => {
    const now = LIVE_WINDOW_MS + 100_000;
    const stale = applyDeltaToBuckets([], 0, 5, now - LIVE_WINDOW_MS - 1); // will be filtered immediately below
    const buckets = applyDeltaToBuckets(stale, now, 1, now);
    expect(buckets.find((b) => b.bucketStart === 0)).toBeUndefined();
    expect(buckets.some((b) => b.units === 1)).toBeTrue();
  });
});

describe('toLineChartSeries', () => {
  it('wraps buckets into a single ngx-charts series', () => {
    const result = toLineChartSeries('API calls', [{ bucketStart: 10_000, units: 4 }]);
    expect(result.length).toBe(1);
    expect(result[0].name).toBe('API calls');
    expect(result[0].series).toEqual([{ name: new Date(10_000), value: 4 }]);
  });
});
