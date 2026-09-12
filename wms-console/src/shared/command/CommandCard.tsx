import { ReactNode, useMemo, useState } from "react";
import { Button, Card, Form } from "antd";
import { rememberKey } from "../../api/client";
import { asRecord, field, type ItemRecord } from "../../api/envelope";
import { errorBanner } from "../ui/errorBanner";
import { StatusBanner } from "../ui/StatusBanner";

export function CommandCard({
  title,
  hint,
  operation,
  submitLabel,
  disabled,
  embedded,
  children,
  onRun,
  onDone
}: {
  title: string;
  hint: string;
  operation: string;
  submitLabel: string;
  disabled?: boolean;
  embedded?: boolean;
  children: ReactNode;
  onRun: (idempotencyKey: string, values: Record<string, string>) => Promise<unknown>;
  onDone?: () => void;
}) {
  const [generation, setGeneration] = useState(0);
  const key = useMemo(() => rememberKey(`${operation}:${generation}`), [operation, generation]);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();

  async function submit(values: Record<string, string>) {
    setBusy(true);
    try {
      const body = asRecord(await onRun(key, values));
      setResult(body);
      setError(undefined);
      setGeneration((current) => current + 1);
      onDone?.();
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  const accepted = Boolean(result && (result.physicalStatus || result.stockSyncStatus || result.operationId));
  const body = (
    <>
      {embedded ? <h3 style={{ marginTop: 0, fontSize: 14 }}>{title}</h3> : null}
      <p style={{ color: "rgba(0,0,0,0.45)", marginTop: 0 }}>{hint}</p>
      {error ? errorBanner(error) : null}
      {accepted ? (
        <StatusBanner
          kind={field(result ?? {}, "stockSyncStatus") === "PENDING" ? "sync-pending" : "accepted"}
          title={field(result ?? {}, "physicalStatus") || "命令已受理"}
          operationId={field(result ?? {}, "operationId", "commandId", "taskId")}
          detail={field(result ?? {}, "stockSyncStatus") ? `stockSyncStatus=${field(result ?? {}, "stockSyncStatus")}` : undefined}
        />
      ) : null}
      {result && !accepted ? (
        <StatusBanner kind="success" title="已返回最新记录" detail={field(result, "status", "state")} />
      ) : null}
      <Form
        layout="vertical"
        size="small"
        requiredMark="optional"
        onFinish={(values) => void submit(values as Record<string, string>)}
        disabled={disabled || busy}
      >
        {children}
        <Button type="primary" htmlType="submit" loading={busy} disabled={disabled}>
          {submitLabel}
        </Button>
      </Form>
    </>
  );
  return embedded ? <div>{body}</div> : <Card title={title} size="small">{body}</Card>;
}
