import { ReactNode, useMemo, useState } from "react";
import { Button, Card, Form } from "antd";
import { hasScope } from "../../auth/can";
import { rememberKey } from "../../api/client";
import { asRecord, field, type ItemRecord } from "../../api/envelope";
import { useWorkspace } from "../../shell/WorkspaceContext";
import { errorBanner } from "../ui/errorBanner";
import { StatusBanner } from "../ui/StatusBanner";
import { useMarkDirty } from "./dirtyForm";

export function CommandCard({
  title,
  hint,
  operation,
  submitLabel,
  disabled,
  embedded,
  requireScope,
  danger,
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
  requireScope?: string | string[];
  danger?: boolean;
  children: ReactNode;
  onRun: (idempotencyKey: string, values: Record<string, string>) => Promise<unknown>;
  onDone?: () => void;
}) {
  const { scopes } = useWorkspace();
  const markDirty = useMarkDirty();
  const [generation, setGeneration] = useState(0);
  const key = useMemo(() => rememberKey(`${operation}:${generation}`), [operation, generation]);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();

  if (!hasScope(scopes, requireScope)) {
    return null;
  }

  async function submit(values: Record<string, string>) {
    setBusy(true);
    try {
      const body = asRecord(await onRun(key, values));
      setResult(body);
      setError(undefined);
      setGeneration((current) => current + 1);
      markDirty(false);
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
        onValuesChange={() => markDirty(true)}
        onFinish={(values) => void submit(values as Record<string, string>)}
        disabled={disabled || busy}
      >
        {children}
        <Button
          type="primary"
          danger={danger}
          htmlType="submit"
          loading={busy}
          disabled={disabled}
        >
          {submitLabel}
        </Button>
      </Form>
    </>
  );
  return embedded ? <div>{body}</div> : <Card title={title} size="small">{body}</Card>;
}
