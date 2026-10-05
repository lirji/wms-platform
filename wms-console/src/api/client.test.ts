import { describe, expect, it, vi } from 'vitest';
import { api, routeFor, serviceFor } from './client';

describe('API routing', () => {
  it('sends inbound receipts to inbound', () => {
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/inbound-orders')).toBe('inbound');
    expect(routeFor('/api/wms/v1/warehouses/WH-A/inbound-orders')).toContain('/inbound-api/');
  });

  it('keeps inventory queries on inventory', () => {
    expect(serviceFor('/api/wms/v1/inventory')).toBe('inventory');
    expect(serviceFor('/api/wms/v1/skus')).toBe('inventory');
    expect(serviceFor('/api/wms/v1/jobs?warehouseId=WH-A')).toBe('inventory');
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/count-plans/P1/freeze-requests')).toBe(
      'inventory',
    );
  });

  it('sends outbound picks and cancellations to outbound', () => {
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/tasks/T1/picks')).toBe('outbound');
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/outbound-orders/O1/cancellations')).toBe(
      'outbound',
    );
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/outbound-orders/O1/pick-tasks')).toBe(
      'outbound',
    );
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/outbound-orders/O1/shippable-serials')).toBe(
      'outbound',
    );
  });

  it('keeps selectable serial stock on inventory', () => {
    expect(
      serviceFor('/api/wms/v1/warehouses/WH-A/serial-stock?ownerId=OWNER&skuId=SKU&locationId=LOC'),
    ).toBe('inventory');
  });

  it('routes warehouse tasks by required taskType', () => {
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/tasks?taskType=PUTAWAY')).toBe('inbound');
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/tasks/T1?taskType=PUTAWAY')).toBe('inbound');
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/tasks/T1/claims?taskType=PICK')).toBe(
      'outbound',
    );
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/tasks?taskType=RESTOCK')).toBe('outbound');
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/tasks/T1/action-effects?taskType=PICK')).toBe(
      'inventory',
    );
  });

  it('sends transfer receipts to fulfillment', () => {
    expect(serviceFor('/api/wms/v1/warehouses/WH-B/transfer-receipts')).toBe('fulfillment');
    expect(serviceFor('/api/wms/v1/warehouses/WH-B/serial-transfer-receipts')).toBe('fulfillment');
    expect(serviceFor('/api/wms/v1/transfers/TR-1/receipt-authorizations')).toBe('fulfillment');
    expect(serviceFor('/api/wms/v1/transfers/TR-1/issues')).toBe('fulfillment');
    expect(serviceFor('/api/wms/v1/transfers/TR-1/serial-issues')).toBe('fulfillment');
    expect(serviceFor('/api/wms/v1/transfers/TR-1/serial-commands/CMD-1')).toBe('fulfillment');
    expect(serviceFor('/api/wms/v1/fulfillments/F1/attempts')).toBe('fulfillment');
  });

  it('routes serial recoveries and hinted message queues', () => {
    expect(serviceFor('/api/wms/v1/warehouses/WH-A/serial-recoveries')).toBe('inventory');
    expect(serviceFor('/api/wms/v1/operations/OP-1')).toBe('inventory');
    expect(
      serviceFor('/api/wms/v1/warehouses/WH-A/message-queues/INBOX/messages?service=inbound'),
    ).toBe('inbound');
    expect(
      routeFor('/api/wms/v1/warehouses/WH-A/message-queues/INBOX/messages?service=inbound'),
    ).toBe('/inbound-api/api/wms/v1/warehouses/WH-A/message-queues/INBOX/messages');
  });
});

it('bare resource-server 401 invalidates old hints with its real status', async () => {
  const events: unknown[] = [];
  const listener = (event: Event) => events.push((event as CustomEvent).detail);
  window.addEventListener('wms:access-invalidated', listener);
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })));
  try {
    await expect(api('/api/wms/v1/skus', 'expired')).rejects.toMatchObject({ status: 401 });
    expect(events).toEqual([expect.objectContaining({ status: 401 })]);
  } finally {
    window.removeEventListener('wms:access-invalidated', listener);
    vi.unstubAllGlobals();
  }
});
