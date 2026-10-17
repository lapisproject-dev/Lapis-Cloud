// Route table of the encounter-space RPC service (Kilua RPC 0.0.45): one POST route per method,
// "/rpc/route<Manager><index>", index = 0-based declaration order in IEncounterSpaceService.
// The table lives in routes.json and is pinned to the interface by LoadTestRouteIndexTripwireTest.
import { readFileSync } from 'node:fs';

const table = JSON.parse(readFileSync(new URL('./routes.json', import.meta.url), 'utf8'));

export const METHODS = Object.freeze({ ...table.methods });

export function routePath(method) {
  if (!Object.hasOwn(METHODS, method)) {
    throw new Error(`unknown RPC method: ${String(method).slice(0, 40)}`);
  }
  return `/rpc/route${table.manager}${METHODS[method]}`;
}
