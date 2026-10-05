import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { api } from '../api/client';
import { canOperation } from './can';
import {
  menuRouteFor,
  readWorkspaceAccess,
  useWorkspaceAccess,
  workspaceRouteAllowed,
} from './workspaceAccess';

vi.mock('../api/client', () => ({ api: vi.fn() }));
const access = {
  mode: 'CENTRAL',
  enterpriseId: 'ENT-DEMO',
  warehouseIds: ['WH-A'],
  scopes: ['masterdata.write'],
  capabilities: ['wms.masterdata.write.enterprise'],
  menus: [],
  observedAt: '2026-10-04T00:00:00Z',
};
beforeEach(() => vi.clearAllMocks());

test('enterprise-only catalog does not require or invent warehouse authority', () => {
  const enterprise = readWorkspaceAccess({
    ...access,
    warehouseIds: [],
    capabilities: ['wms.masterdata.read.enterprise'],
    menus: [{ code: 'catalog', parent: null, route: '/catalog', label: '商品', position: 1 }],
  });
  expect(workspaceRouteAllowed(enterprise, '_', '/w/_/catalog')).toBe(true);
  expect(workspaceRouteAllowed(enterprise, '_', '/w/_/catalog/skus/SKU-1')).toBe(true);
  expect(workspaceRouteAllowed(enterprise, '_', '/w/_/catalog/locations/LOC-1')).toBe(false);
  expect(workspaceRouteAllowed(enterprise, 'WH-B', '/w/WH-B/catalog')).toBe(false);
  expect(workspaceRouteAllowed(enterprise, '_', '/pda/_/receive')).toBe(false);
});

test('enterprise action does not enable warehouse write sharing the legacy scope', () => {
  const workspace = {
    ...readWorkspaceAccess(access),
    warehouseId: 'WH-A',
    scopes: ['masterdata.write'],
  };
  expect(canOperation(workspace, 'masterdata.write', 'enterprise')).toBe(true);
  expect(canOperation(workspace, 'masterdata.write')).toBe(false);
  expect(
    canOperation(
      { ...workspace, capabilities: ['wms.masterdata.write'] },
      'masterdata.write',
      'enterprise',
    ),
  ).toBe(false);
  expect(
    canOperation(
      { warehouseId: 'WH-A', mode: 'LEGACY', scopes: ['masterdata.write'] },
      'masterdata.write',
    ),
  ).toBe(true);
});
test('malformed and unknown modes cannot provide permissions', () => {
  for (const invalid of [
    {},
    { ...access, mode: 'UNKNOWN' },
    { ...access, capabilities: null },
    { ...access, menus: [{ route: 1 }] },
  ]) {
    expect(() => readWorkspaceAccess(invalid)).toThrow();
  }
});
test('all document links map to the owning menu; unknown pages stay distinct', () => {
  expect(menuRouteFor('/w/WH-A/catalog/skus/1')).toBe('/catalog');
  expect(menuRouteFor('/w/WH-A/outbound/1')).toBe('/fulfillment');
  expect(menuRouteFor('/w/WH-A/effects/1')).toBe('/jobs');
  expect(menuRouteFor('/pda/WH-A/ship')).toBe('/pda/ship');
  expect(menuRouteFor('/w/WH-A/admin')).toBe('/admin');
});
test('warehouse change clears previous rights and ignores a late previous response', async () => {
  let complete!: (value: unknown) => void;
  vi.mocked(api)
    .mockResolvedValueOnce(access)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        }),
    );
  const { result, rerender } = renderHook(
    ({ warehouse }) => useWorkspaceAccess('token', warehouse),
    { initialProps: { warehouse: 'WH-A' } },
  );
  await waitFor(() => expect(result.current.access?.warehouseIds).toEqual(['WH-A']));
  rerender({ warehouse: 'WH-B' });
  expect(result.current.access).toBeUndefined();
  await act(async () => complete({ ...access, warehouseIds: ['WH-B'] }));
  expect(result.current.access?.warehouseIds).toEqual(['WH-B']);
});
test('revocation or dependency failure clears rights and explicit retry reloads', async () => {
  vi.mocked(api)
    .mockResolvedValueOnce(access)
    .mockRejectedValueOnce({ status: 503 })
    .mockResolvedValueOnce({ ...access, capabilities: [], scopes: [] });
  const { result } = renderHook(() => useWorkspaceAccess('token', 'WH-A'));
  await waitFor(() => expect(result.current.access).toBeDefined());
  act(() => window.dispatchEvent(new Event('wms:access-invalidated')));
  expect(result.current.access).toBeUndefined();
  expect(result.current.error).toBeDefined();
  act(() => result.current.retry());
  await waitFor(() => expect(result.current.error).toEqual({ status: 503 }));
  act(() => result.current.retry());
  await waitFor(() => expect(result.current.access?.capabilities).toEqual([]));
});
