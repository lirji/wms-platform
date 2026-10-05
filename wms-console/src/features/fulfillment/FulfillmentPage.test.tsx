import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from '../../app/AppProviders';
import { WorkspaceProvider } from '../../shell/WorkspaceContext';
import { FulfillmentPage } from './FulfillmentPage';

describe('FulfillmentPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('maps fulfillment and outbound first-line snake_case columns', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const path = String(url);
        if (path.includes('/fulfillments')) {
          return new Response(
            JSON.stringify({
              items: [
                {
                  id: 'FF-DEMO-OPEN',
                  status: 'OPEN',
                  sku_id: 'SKU-STD',
                  requested_qty: '8',
                  source_order_no: 'SO-DEMO-OPEN',
                },
              ],
            }),
            { status: 200 },
          );
        }
        return new Response(
          JSON.stringify({
            items: [
              {
                id: 'OB-DEMO-ALLOC-A',
                status: 'ALLOCATED',
                sku_id: 'SKU-STD',
                allocated_qty: '6',
                picked_physical_qty: '0',
                stock_sync_status: 'PENDING',
              },
            ],
          }),
          { status: 200 },
        );
      }),
    );
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: 't', warehouseId: 'WH-A', scopes: [] }}>
            <FulfillmentPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>,
    );
    await waitFor(() => expect(screen.getAllByText('SKU-STD').length).toBeGreaterThan(0));
    expect(screen.getByText('8')).toBeTruthy();
    expect(screen.getByText('6')).toBeTruthy();
    expect(screen.getByText('SO-DEMO-OPEN')).toBeTruthy();
  });
});
