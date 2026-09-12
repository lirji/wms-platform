import { ReactNode, useEffect, useMemo, useState } from "react";
import { Button, Card, Form } from "antd";
import { hasScope } from "../../auth/can";
import { api, rememberKey } from "../../api/client";
import { asRecord, field, type ItemRecord } from "../../api/envelope";
import { useWorkspace } from "../../shell/WorkspaceContext";
import { errorBanner } from "../ui/errorBanner";
import { StatusBanner } from "../ui/StatusBanner";
import { useMarkDirty } from "./dirtyForm";
import { httpStatusOf, isSyncPending, isTerminalSuccess } from "./accepted";

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
  const { token, scopes } = useWorkspace();
  const markDirty = useMarkDirty();
  const [generation, setGeneration] = useState(0);
  const key = useMemo(() => rememberKey(`${operation}:${generation}`), [operation, generation]);
  const [busy, setBusy] = useState(false);
  const [rateLimited, setRateLimited] = useState(false);
  const [result, setResult] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();
  const pollId = result && isSyncPending(result) ? field(result, "operationId", "commandId") : "";

  useEffect(() => {
    if (!token || !pollId) {
      return;
    }
    let cancelled = false;
    let delay = 800;
    let attempts = 0;
    function tick() {
      if (cancelled || attempts >= 5) {
        return;
      }
      attempts += 1;
      window.setTimeout(() => {
        api(`/api/wms/v1/operations/${encodeURIComponent(pollId)}`, token)
          .then((body) => {
            if (cancelled) {
              return;
            }
            const latest = asRecord(body);
            setResult((current) => ({ ...(current ?? {}), ...latest }));
            if (isTerminalSuccess(latest)) {
              setGeneration((current) => current + 1);
              onDone?.();
              return;
            }
            delay = Math.min(delay * 2, 4000);
            tick();
          })
          .catch(() => {
            if (!cancelled) {
              delay = Math.min(delay * 2, 4000);
              tick();
            }
          });
      }, delay);
    }
    tick();
    return () => {
      cancelled = true;
    };
  }, [pollId, token]);

  if (!hasScope(scopes, requireScope)) {
    return null;
  }

  async function submit(values: Record<string, string>) {
    setBusy(true);
    try {
      const body = asRecord(await onRun(key, values));
      setResult(body);
      setError(undefined);
      if (isTerminalSuccess(body) && httpStatusOf(body) < 300) {
        setGeneration((current) => current + 1);
      }
      markDirty(false);
      onDone?.();
    } catch (caught) {
      const status = (caught as { status?: number }).status;
      setError(caught);
      if (status === 429) {
        setRateLimited(true);
        window.setTimeout(() => setRateLimited(false), 4000);
      }
    } finally {
      setBusy(false);
    }
  }

  const pending = Boolean(result && isSyncPending(result));
  const accepted = Boolean(result && (result.physicalStatus || result.stockSyncStatus || result.operationId || pending));
  const body = (
    <>
      {embedded ? <h3 style={{ marginTop: 0, fontSize: 14 }}>{title}</h3> : null}
      <p style={{ color: "rgba(0,0,0,0.45)", marginTop: 0 }}>{hint}</p>
      {error ? errorBanner(error) : null}
      {accepted ? (
        <StatusBanner
          kind={pending ? "sync-pending" : "accepted"}
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
          disabled={disabled || rateLimited}
        >
          {submitLabel}
        </Button>
      </Form>
    </>
  );
  return embedded ? <div>{body}</div> : <Card title={title} size="small">{body}</Card>;
}
