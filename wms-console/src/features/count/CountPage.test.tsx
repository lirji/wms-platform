import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from '../../app/AppProviders';
import { WorkspaceProvider } from '../../shell/WorkspaceContext';
import { CountPage } from './CountPage';

describe('CountPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows count plan reason and first scope location', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              items: [
                {
                  id: 'CNT-DEMO-DRAFT-WH-A',
                  status: 'DRAFT',
                  reason_code: 'COUNT',
                  location_id: 'WH-A-STO',
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
          <WorkspaceProvider value={{ token: 't', warehouseId: 'WH-A', scopes: [] }}>
            <CountPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>,
    );
    await waitFor(() => expect(screen.getByText('WH-A-STO')).toBeTruthy());
    expect(screen.getByText('COUNT')).toBeTruthy();
  });
});
