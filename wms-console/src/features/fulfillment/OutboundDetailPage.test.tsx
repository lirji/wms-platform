import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from '../../app/AppProviders';
import { WorkspaceProvider } from '../../shell/WorkspaceContext';
import { OutboundDetailPage } from './OutboundDetailPage';

describe('OutboundDetailPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('maps outbound GET header and line quantities including version', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              id: 'OB-1',
              status: 'PENDING_AUTHORIZATION',
              allocationId: 'ALLOC-1',
              attemptId: 'ATT-1',
              executionAuthorizationId: null,
              version: 1,
              lines: [
                {
                  id: 'OL-1',
                  sku_id: 'SKU-STD',
                  allocated_qty: '6',
                  picked_physical_qty: '0',
                  packed_physical_qty: '0',
                  shipped_physical_qty: '0',
                  cancelled_qty: '0',
                  stock_sync_status: 'IDLE',
                },
              ],
              tasks: [],
            }),
            { status: 200 },
          ),
      ),
    );
    render(
      <AppProviders>
        <MemoryRouter initialEntries={['/w/WH-A/outbound/OB-1']}>
          <WorkspaceProvider value={{ token: 't', warehouseId: 'WH-A', scopes: ['outbound.pick'] }}>
            <Routes>
              <Route
                path="/w/:warehouseId/outbound/:outboundOrderId"
                element={<OutboundDetailPage />}
              />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>,
    );
    await waitFor(() => expect(screen.getByText('ALLOC-1')).toBeTruthy());
    expect(screen.getByText('执行授权')).toBeTruthy();
    expect(screen.getByText('SKU-STD')).toBeTruthy();
    expect(screen.getAllByText('已分配').length).toBeGreaterThan(0);
    expect(screen.getByText('6')).toBeTruthy();
    expect(screen.queryByText(/^实物$/)).toBeNull();
  });

  it('exposes pick, pack, ship and cancel commands', { timeout: 30_000 }, () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={['/w/WH-A/outbound/OB-1']}>
          <WorkspaceProvider
            value={{
              token: 't',
              warehouseId: 'WH-A',
              scopes: ['outbound.pick', 'outbound.pack', 'outbound.ship', 'fulfillment.execute'],
            }}
          >
            <Routes>
              <Route
                path="/w/:warehouseId/outbound/:outboundOrderId"
                element={<OutboundDetailPage />}
              />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>,
    );
    fireEvent.click(screen.getByRole('button', { name: '提交命令' }));
    expect(screen.getByRole('button', { name: '提交授权' })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: /^规划拣货$/ }));
    expect(screen.getByRole('button', { name: '规划任务' })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: /^拣货$/ }));
    expect(screen.getByRole('button', { name: '确认拣货' })).toBeTruthy();
    expect(screen.getByText('出库身份')).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: /^包装$/ }));
    expect(screen.getByRole('button', { name: '确认包装' })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: /^部分发运$/ }));
    expect(screen.getByRole('button', { name: '确认发运' })).toBeTruthy();
    expect(screen.getAllByText('出库身份').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('tab', { name: /^取消未拣$/ }));
    expect(screen.getByRole('button', { name: '取消剩余' })).toBeTruthy();
    expect(screen.getByText('可选序列号')).toBeTruthy();
    expect(screen.getByText('可发运序列号')).toBeTruthy();
  });
});
