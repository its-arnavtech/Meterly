/** A rolling live series of delta units, bucketed by wall-clock time. */
export interface LiveBucket {
  /** Epoch ms — start of this bucket's 10s window. */
  bucketStart: number;
  units: number;
}

export const LIVE_BUCKET_MS = 10_000;
export const LIVE_WINDOW_MS = 15 * 60 * 1000;
const MAX_BUCKETS = LIVE_WINDOW_MS / LIVE_BUCKET_MS;

/**
 * Folds one STOMP delta into a rolling 15-minute window of 10s buckets, returning a new
 * array (buckets are immutable so Angular change detection sees the update). Buckets older
 * than the window are dropped.
 */
export function applyDeltaToBuckets(buckets: LiveBucket[], occurredAtMs: number, delta: number, now = Date.now()): LiveBucket[] {
  const bucketStart = Math.floor(occurredAtMs / LIVE_BUCKET_MS) * LIVE_BUCKET_MS;
  const next = buckets.slice();
  const idx = next.findIndex((b) => b.bucketStart === bucketStart);
  if (idx >= 0) {
    next[idx] = { bucketStart, units: next[idx].units + delta };
  } else {
    next.push({ bucketStart, units: delta });
    next.sort((a, b) => a.bucketStart - b.bucketStart);
  }
  const cutoff = now - LIVE_WINDOW_MS;
  return next.filter((b) => b.bucketStart >= cutoff).slice(-MAX_BUCKETS);
}

export interface NgxChartSeriesPoint {
  name: Date;
  value: number;
}

export interface NgxChartSeries {
  name: string;
  series: NgxChartSeriesPoint[];
}

/** Adapts our bucket list to ngx-charts' `results` input for a single-series line chart. */
export function toLineChartSeries(seriesName: string, buckets: LiveBucket[]): NgxChartSeries[] {
  return [
    {
      name: seriesName,
      series: buckets.map((b) => ({ name: new Date(b.bucketStart), value: b.units }))
    }
  ];
}
