import { Pipe, PipeTransform } from '@angular/core';
import { Money } from '../../core/models';

export type MoneyMode = 'standard' | 'unit';

interface ParsedMoney {
  negative: boolean;
  intPart: string;
  fracPart: string;
}

const THOUSANDS = /\B(?=(\d{3})+(?!\d))/g;

function parseMoney(value: string): ParsedMoney {
  const negative = value.trim().startsWith('-');
  const unsigned = value.trim().replace(/^[-+]/, '');
  const [intPart, fracPart = ''] = unsigned.split('.');
  return { negative, intPart: intPart || '0', fracPart };
}

/** Rounds `fracPart` to 2 digits, HALF_EVEN, using BigInt digit math — never a float. */
function roundToTwoDp(intPart: string, fracPart: string): { intPart: string; frac: string } {
  const padded = fracPart.padEnd(2, '0');
  const kept = padded.slice(0, 2);
  const remainder = padded.slice(2);

  if (!remainder || /^0*$/.test(remainder)) {
    return { intPart, frac: kept };
  }

  const half = '5'.padEnd(remainder.length, '0');
  let roundUp: boolean;
  if (remainder > half) {
    roundUp = true;
  } else if (remainder < half) {
    roundUp = false;
  } else {
    // Exactly half: round to even (banker's rounding), matching billing-service's HALF_EVEN.
    const lastKeptDigit = Number(kept[1] ?? '0');
    roundUp = lastKeptDigit % 2 !== 0;
  }

  if (!roundUp) {
    return { intPart, frac: kept };
  }

  const bumped = BigInt(kept) + 1n;
  if (bumped >= 100n) {
    const nextInt = (BigInt(intPart || '0') + 1n).toString();
    return { intPart: nextInt, frac: '00' };
  }
  return { intPart, frac: bumped.toString().padStart(2, '0') };
}

/** Trims trailing zeros from a unit-price fraction, but never below 4 decimal places. */
function trimUnitFraction(fracPart: string): string {
  let frac = fracPart.padEnd(4, '0');
  while (frac.length > 4 && frac.endsWith('0')) {
    frac = frac.slice(0, -1);
  }
  return frac;
}

function withThousands(intPart: string): string {
  return intPart.replace(THOUSANDS, ',');
}

/**
 * Formats a decimal money string (docs/api-contract.md §0: JSON strings, never numbers)
 * without ever parsing to a float. `standard` rounds/pads to 2dp with thousands separators
 * (e.g. "1234567.89" -> "$1,234,567.89"); `unit` preserves precision for per-unit prices,
 * trimming trailing zeros down to a minimum of 4dp (e.g. "0.002000" -> "$0.0020").
 */
@Pipe({ name: 'money', standalone: true })
export class MoneyPipe implements PipeTransform {
  transform(value: Money | null | undefined, mode: MoneyMode = 'standard', currency = '$'): string {
    if (value === null || value === undefined || value === '') {
      return '—';
    }
    const parsed = parseMoney(String(value));
    const sign = parsed.negative ? '-' : '';

    if (mode === 'unit') {
      const frac = trimUnitFraction(parsed.fracPart);
      return `${sign}${currency}${withThousands(parsed.intPart)}.${frac}`;
    }

    const { intPart, frac } = roundToTwoDp(parsed.intPart, parsed.fracPart);
    return `${sign}${currency}${withThousands(intPart)}.${frac}`;
  }
}
