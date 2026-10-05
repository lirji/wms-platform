import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from '../../app/AppProviders';
import { WorkspaceProvider } from '../../shell/WorkspaceContext';
import { CountDetailPage } from './CountDetailPage';

describe('CountDetailPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('maps count GET reason, locations and lines without inventing SKU', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              id: 'CP-1',
              status: 'FROZEN',
              reasonCode: 'CYCLE',
              approvalId: null,
              version: 2,
              locations: [{ locationId: 'WH-A-ST-01', gateEpoch: 1 }],
              lines: [
                {
                  id: 'CL-1',
                  locationId: 'WH-A-ST-01',
                  balanceId: 'BAL-1',
                  snapshotQty: '5',
                  reservedQty: '0',
                  countedQty: null,
                  status: 'OPEN',
                },
              ],
            }),
            { status: 200 },
          ),
      ),
    );
    render(
      <AppProviders>
        <MemoryRouter initialEntries={['/w/WH-A/counts/CP-1']}>
          <WorkspaceProvider value={{ token: 't', warehouseId: 'WH-A', scopes: ['count.record'] }}>
            <Routes>
              <Route path="/w/:warehouseId/counts/:countPlanId" element={<CountDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>,
    );
    await waitFor(() => expect(screen.getByText('CYCLE')).toBeTruthy());
    expect(screen.getByText('原因')).toBeTruthy();
    expect(screen.getAllByText('WH-A-ST-01').length).toBeGreaterThan(0);
    expect(screen.getAllByText('快照').length).toBeGreaterThan(0);
    expect(screen.getByText('5')).toBeTruthy();
    expect(screen.queryByText('SKU')).toBeNull();
    expect(screen.queryByText('库存同步')).toBeNull();
  });

  it('exposes identity observation for serial count lines', { timeout: 30_000 }, () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={['/w/WH-A/counts/CP-1']}>
          <WorkspaceProvider value={{ token: 't', warehouseId: 'WH-A', scopes: ['count.record'] }}>
            <Routes>
              <Route path="/w/:warehouseId/counts/:countPlanId" element={<CountDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>,
    );
    fireEvent.click(screen.getByRole('button', { name: '提交命令' }));
    fireEvent.click(screen.getByRole('tab', { name: '点数' }));
    expect(screen.getByRole('checkbox', { name: /全部未见/ })).toBeTruthy();
    expect(screen.getByText('实见身份')).toBeTruthy();
  });
});
