import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from '../../app/AppProviders';
import { WorkspaceProvider } from '../../shell/WorkspaceContext';
import { InboundPage } from './InboundPage';

function renderInbound(initialEntry = '/w/WH-A/inbound') {
  return render(
    <AppProviders>
      <MemoryRouter initialEntries={[initialEntry]}>
        <WorkspaceProvider
          value={{
            token: 'fixture-token',
            warehouseId: 'WH-A',
            scopes: ['inbound.create', 'inbound.read'],
          }}
        >
          <InboundPage />
        </WorkspaceProvider>
      </MemoryRouter>
    </AppProviders>,
  );
}

function fillInbound(dialog: HTMLElement, externalNo = 'EXT-PILOT-1') {
  fireEvent.change(within(dialog).getByLabelText('外部单号'), { target: { value: externalNo } });
  fireEvent.change(within(dialog).getByLabelText('SKU'), { target: { value: 'SKU-PILOT' } });
  fireEvent.change(within(dialog).getByLabelText('应收数量'), { target: { value: '1.250000' } });
}

describe('inbound create recovery', () => {
  afterEach(() => {
    sessionStorage.clear();
    vi.unstubAllGlobals();
  });

  it('preserves refused input and leave protection without refreshing before confirmation', async () => {
    let listReads = 0;
    let reply: (value: Response) => void = () => undefined;
    const keys: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        if (!String(url).startsWith('/inbound-api/api/wms/v1/warehouses/WH-A/inbound-orders'))
          throw new Error('unexpected fixture request');
        if (init?.method !== 'POST') {
          listReads += 1;
          return new Response(JSON.stringify({ items: [] }), { status: 200 });
        }
        keys.push((init.headers as Record<string, string>)['Idempotency-Key']);
        return new Promise<Response>((resolve) => {
          reply = resolve;
        });
      }),
    );
    renderInbound();
    await waitFor(() => expect(listReads).toBe(1));
    fireEvent.click(screen.getByRole('button', { name: '创建入库单' }));
    const dialog = screen.getByRole('dialog', { name: '创建入库单' });
    fillInbound(dialog);
    fireEvent.click(within(dialog).getByRole('button', { name: '创建入库单' }));
    await waitFor(() => expect(keys.length).toBe(1));
    expect(listReads).toBe(1);
    expect(within(dialog).getByText('正在提交，请等待服务端结果后再关闭。')).toBeTruthy();
    reply(
      new Response(JSON.stringify({ code: 'DUPLICATE_DOCUMENT', message: '入库单已存在' }), {
        status: 409,
      }),
    );
    await waitFor(() => expect(within(dialog).getByText('单据或明细行已存在')).toBeTruthy());
    expect(document.activeElement).toBe(within(dialog).getByRole('group', { name: '提交未完成' }));
    expect((within(dialog).getByLabelText('外部单号') as HTMLInputElement).value).toBe(
      'EXT-PILOT-1',
    );
    expect((within(dialog).getByLabelText('应收数量') as HTMLInputElement).value).toBe('1.250000');
    expect(listReads).toBe(1);
    fireEvent.click(within(dialog).getByRole('button', { name: /Close|关闭/ }));
    expect((await screen.findAllByText('放弃未提交的内容？')).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: '继续编辑' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '继续编辑' })).toBeNull());
    fireEvent.change(within(dialog).getByLabelText('外部单号'), {
      target: { value: 'EXT-PILOT-FIXED' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '创建入库单' }));
    await waitFor(() => expect(keys.length).toBe(2));
    expect(keys[1]).toBe(keys[0]);
    reply(
      new Response(JSON.stringify({ code: 'INVALID_REQUEST', message: '货主不存在' }), {
        status: 400,
      }),
    );
    await waitFor(() => expect(within(dialog).getByText(/货主不存在/)).toBeTruthy());
    expect(document.activeElement).toBe(within(dialog).getByRole('group', { name: '提交未完成' }));
    expect((within(dialog).getByLabelText('外部单号') as HTMLInputElement).value).toBe(
      'EXT-PILOT-FIXED',
    );
  });

  it('refreshes after 201, reveals the new row after filter/cursor and continues with a new key', async () => {
    let created = false;
    let listReads = 0;
    let reply: (value: Response) => void = () => undefined;
    const posts: { key: string; body: Record<string, unknown> }[] = [];
    const listUrls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        if (!String(url).startsWith('/inbound-api/api/wms/v1/warehouses/WH-A/inbound-orders'))
          throw new Error('unexpected fixture request');
        if (init?.method !== 'POST') {
          listReads += 1;
          listUrls.push(String(url));
          return new Response(
            JSON.stringify({
              items: created
                ? [
                    {
                      id: 'INB-NEW',
                      status: 'APPROVED',
                      sku_id: 'SKU-PILOT',
                      expected_qty: '1.250000',
                      received_physical_qty: '0',
                      stock_sync_status: 'PENDING',
                    },
                  ]
                : [],
            }),
            { status: 200 },
          );
        }
        posts.push({
          key: (init.headers as Record<string, string>)['Idempotency-Key'],
          body: JSON.parse(String(init.body)),
        });
        return new Promise<Response>((resolve) => {
          reply = resolve;
        });
      }),
    );
    renderInbound('/w/WH-A/inbound?q=not-this-order&cursor=fixture-cursor');
    await waitFor(() => expect(listReads).toBe(1));
    fireEvent.click(screen.getByRole('button', { name: '创建入库单' }));
    const dialog = screen.getByRole('dialog', { name: '创建入库单' });
    fillInbound(dialog);
    fireEvent.click(within(dialog).getByRole('button', { name: '创建入库单' }));
    await waitFor(() => expect(posts.length).toBe(1));
    expect(listReads).toBe(1);
    expect((posts[0].body.lines as Record<string, unknown>[])[0].expectedQty).toBe('1.250000');
    created = true;
    reply(
      new Response(JSON.stringify({ orderId: 'INB-NEW', status: 'APPROVED' }), { status: 201 }),
    );
    await waitFor(() => expect(within(dialog).getByText('入库单已创建')).toBeTruthy());
    await waitFor(() => expect(listReads).toBeGreaterThan(1));
    await waitFor(() => expect(listUrls.at(-1)).not.toContain('cursor='));
    await waitFor(() =>
      expect(screen.getByRole('link', { name: /INB-NEW/ }).getAttribute('href')).toBe(
        '/w/WH-A/inbound/INB-NEW',
      ),
    );
    expect(within(dialog).getByRole('button', { name: '打开单据' })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: '继续创建' }));
    expect((within(dialog).getByLabelText('外部单号') as HTMLInputElement).value).toBe('');
    fillInbound(dialog, 'EXT-PILOT-2');
    fireEvent.click(within(dialog).getByRole('button', { name: '创建入库单' }));
    await waitFor(() => expect(posts.length).toBe(2));
    expect(posts[1].key).not.toBe(posts[0].key);
    reply(
      new Response(JSON.stringify({ orderId: 'INB-SECOND', status: 'APPROVED' }), { status: 201 }),
    );
    await waitFor(() => expect(within(dialog).getByText('单据：INB-SECOND')).toBeTruthy());
    fireEvent.click(within(dialog).getByRole('button', { name: '返回列表' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '创建入库单' })).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: '创建入库单' }));
    expect((screen.getByLabelText('外部单号') as HTMLInputElement).value).toBe('');
  });
});
