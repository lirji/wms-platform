import type { ReactNode } from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { expect, test, vi, beforeEach } from 'vitest';
import { api } from '../api/client';
import { canQuery } from '../auth/can';
import { WorkspaceProvider, type WorkspaceValue } from '../shell/WorkspaceContext';
import { useResource } from './useResource';
import { useDocument } from './useDocument';

vi.mock('../api/client', () => ({ api: vi.fn() }));
beforeEach(() => vi.clearAllMocks());
const value: WorkspaceValue = {
  warehouseId: 'WH-A',
  mode: 'CENTRAL',
  capabilities: ['wms.masterdata.read.enterprise'],
};
function wrapper({ children }: { children: ReactNode }) {
  return <WorkspaceProvider value={value}>{children}</WorkspaceProvider>;
}

test('partial menu permissions only issue covered reads and preserve payload positions', async () => {
  vi.mocked(api).mockResolvedValue({ items: [{ id: 'SKU-1' }] });
  const { result } = renderHook(
    () => useResource('token', ['/api/wms/v1/skus', '/api/wms/v1/warehouses/WH-A/locations']),
    { wrapper },
  );
  await waitFor(() => expect(result.current.loading).toBe(false));
  expect(api).toHaveBeenCalledTimes(1);
  expect(result.current.payloads[0]).toEqual({ items: [{ id: 'SKU-1' }] });
  expect(result.current.payloads[1]).toBeUndefined();
  expect(result.current.error).toMatchObject({ status: 403, code: 'QUERY_NOT_GRANTED' });
});
test('unknown read and ungranted detail are local hints; legacy still requests backend', async () => {
  expect(canQuery(value, '/api/wms/v1/future')).toBe(false);
  expect(canQuery(value, '/api/wms/v1/skus/SKU-1?limit=20')).toBe(true);
  expect(canQuery({ warehouseId: 'WH-A', mode: 'LEGACY' }, '/api/wms/v1/future')).toBe(true);
  const { result } = renderHook(
    () => useDocument('token', '/api/wms/v1/warehouses/WH-A/locations/LOC-1'),
    { wrapper },
  );
  await waitFor(() => expect(result.current.loading).toBe(false));
  expect(api).not.toHaveBeenCalled();
  expect(result.current.record).toEqual({});
});

test('clearing the session also clears the previous query failure', async () => {
  const failure = new Error('读取失败');
  vi.mocked(api).mockRejectedValueOnce(failure);
  const { result, rerender } = renderHook(
    ({ token }: { token?: string }) => useResource(token, ['/api/wms/v1/skus']),
    { initialProps: { token: 'token' as string | undefined }, wrapper },
  );
  await waitFor(() => expect(result.current.error).toBe(failure));
  rerender({ token: undefined });
  await waitFor(() => expect(result.current.error).toBeUndefined());
  expect(result.current.rows).toEqual([]);
  expect(result.current.payloads).toEqual([]);
  expect(result.current.loading).toBe(false);
  expect(api).toHaveBeenCalledTimes(1);
});
