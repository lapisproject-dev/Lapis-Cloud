import './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { METHODS, routePath } from '../lib/routes.mjs';
import { KNOWN_EXCEPTION_TYPES, shortExceptionName } from '../lib/exceptions.mjs';

test('route indices are the 0-based declaration order of IEncounterSpaceService', () => {
  const expected = { listSpaces: 0, getSpace: 1, getEntryInfo: 2, enterSpace: 10, leaveSpace: 11, listPresent: 12, selectSeat: 13, joinTable: 16, leaveTable: 17, tableToken: 18, listTables: 19, floorState: 27, mapEnter: 28, mapSnapshot: 32 };
  for (const [name, index] of Object.entries(expected)) assert.equal(METHODS[name], index, name);
  assert.equal(Object.keys(METHODS).length, 33);
  assert.equal(new Set(Object.values(METHODS)).size, 33);
});

test('paths follow /rpc/route<Manager><index>', () => {
  assert.equal(routePath('joinTable'), '/rpc/routeEncounterSpaceServiceManager16');
  assert.equal(routePath('enterSpace'), '/rpc/routeEncounterSpaceServiceManager10');
});

test('an unknown method throws (also for prototype keys)', () => {
  assert.throws(() => routePath('dropDatabase'), /unknown RPC method/);
  assert.throws(() => routePath('constructor'), /unknown RPC method/);
  assert.throws(() => routePath(undefined), /unknown RPC method/);
});

test('exception names map to short names', () => {
  assert.equal(shortExceptionName('network.lapis.cloud.shared.rpc.ServiceBusyException'), 'ServiceBusy');
  assert.equal(shortExceptionName('java.lang.IllegalStateException'), 'OtherException');
  assert.equal(shortExceptionName(null), 'UnknownException');
  assert.equal(KNOWN_EXCEPTION_TYPES.length, 6);
});
