import { MoneyPipe } from './money.pipe';

describe('MoneyPipe', () => {
  let pipe: MoneyPipe;

  beforeEach(() => {
    pipe = new MoneyPipe();
  });

  it('formats a simple two-decimal amount', () => {
    expect(pipe.transform('0.10')).toBe('$0.10');
  });

  it('inserts thousands separators for large amounts', () => {
    expect(pipe.transform('1234567.89')).toBe('$1,234,567.89');
  });

  it('pads a whole-number amount to two decimals', () => {
    expect(pipe.transform('100')).toBe('$100.00');
    expect(pipe.transform('0')).toBe('$0.00');
  });

  it('rounds half-even down when the kept digit is already even (no float drift)', () => {
    // 10.005 -> kept "00", exactly half, 0 is even -> stays 10.00
    expect(pipe.transform('10.005')).toBe('$10.00');
  });

  it('rounds half-even up when the kept digit is odd', () => {
    // 10.015 -> kept "01", exactly half, 1 is odd -> rounds up to 10.02
    expect(pipe.transform('10.015')).toBe('$10.02');
  });

  it('carries into the integer part when rounding overflows the fraction', () => {
    // 9.995 -> kept "99", exactly half, 9 is odd -> rounds up -> carries to 10.00
    expect(pipe.transform('9.995')).toBe('$10.00');
  });

  it('rounds up unambiguously when the remainder exceeds half', () => {
    expect(pipe.transform('1.239')).toBe('$1.24');
  });

  it('renders a dash for null/undefined/empty values', () => {
    expect(pipe.transform(null)).toBe('—');
    expect(pipe.transform(undefined)).toBe('—');
    expect(pipe.transform('')).toBe('—');
  });

  it('formats unit prices in "unit" mode, trimmed to a minimum of 4dp', () => {
    expect(pipe.transform('0.002000', 'unit')).toBe('$0.0020');
    expect(pipe.transform('0.001500', 'unit')).toBe('$0.0015');
  });

  it('does not trim a unit price below 4 decimal places', () => {
    expect(pipe.transform('0.000000', 'unit')).toBe('$0.0000');
  });

  it('preserves full precision in unit mode when there is nothing to trim', () => {
    expect(pipe.transform('1.123456', 'unit')).toBe('$1.123456');
  });

  it('supports a custom currency symbol', () => {
    expect(pipe.transform('19.00', 'standard', '€')).toBe('€19.00');
  });
});
