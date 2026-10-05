import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from '../../app/AppProviders';
import { WorkspaceProvider } from '../../shell/WorkspaceContext';
import { InboundPage } from './InboundPage';

describe('InboundPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows first-line sku and qty from the inbound list envelope', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              items: [
                {
                  id: 'INB-DEMO-OPEN-A',
                  status: 'APPROVED',
                  sku_id: 'SKU-STD',
                  expected_qty: '30',
                  received_physical_qty: '0',
                  stock_sync_status: 'PENDING',
                },
              ],
            }),
            { status: 200 },
          ),
      ),
    );
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider
            value={{ token: 't', warehouseId: 'WH-A', scopes: ['inbound.create'] }}
          >
            <InboundPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>,
    );
    await waitFor(() => expect(screen.getByText('SKU-STD')).toBeTruthy());
    expect(screen.getByText('30')).toBeTruthy();
    expect(screen.getByText('PENDING')).toBeTruthy();
  });
});
