// Maps the canonical exception class names that Kilua puts into "exceptionType" to short names.
// LoadTestRouteIndexTripwireTest checks that every key is a real class.
import { readFileSync } from 'node:fs';

const table = JSON.parse(readFileSync(new URL('./exceptions.json', import.meta.url), 'utf8'));

export function shortExceptionName(exceptionType) {
  if (typeof exceptionType !== 'string' || exceptionType === '') return 'UnknownException';
  return table[exceptionType] ?? 'OtherException';
}

export const KNOWN_EXCEPTION_TYPES = Object.freeze(Object.keys(table));
