import { ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { Button, Card, Form } from "antd";
import { canOperation } from "../../auth/can";
import { api, clearKey, rememberKey } from "../../api/client";
import { asRecord, field, type ItemRecord } from "../../api/envelope";
import { useWorkspace } from "../../shell/WorkspaceContext";
import { errorBanner } from "../ui/errorBanner";
import { StatusBanner } from "../ui/StatusBanner";
import { useCommandDialog, useMarkDirty } from "./dirtyForm";
import { httpStatusOf, isSyncPending, isTerminalSuccess } from "./accepted";

export function CommandCard({
  title,
  hint,
  operation,
  submitLabel,
  disabled,
  embedded,
  requireScope,
  resourceType,
  danger,
  pollOperation,
  children,
  onRun,
  onDone,
  confirmResult,
  renderSuccess
}: {
  title: string;
  hint: string;
  operation: string;
  submitLabel: string;
  disabled?: boolean;
  embedded?: boolean;
  requireScope?: string | string[];
  resourceType?: "warehouse" | "enterprise";
  danger?: boolean;
  /** 仅库存域 GET /operations/{id} 存在。入出库/履约 202 不要拿库存去猜。 */
  pollOperation?: boolean;
  children: ReactNode;
  onRun: (idempotencyKey: string, values: Record<string, string>) => Promise<unknown>;
  onDone?: (result: ItemRecord) => void;
  /** 同步建单可按公开契约核对回执身份，避免把不完整回执写成已创建。 */
  confirmResult?: (result: ItemRecord) => boolean;
  renderSuccess?: (result: ItemRecord, startNext: () => void) => ReactNode;
}) {
  const workspace = useWorkspace();
  const { token } = workspace;
  const markDirty = useMarkDirty();
  const dialog = useCommandDialog();
  const [form] = Form.useForm();
  const requestVersion = useRef(0);
  const submitting = useRef(false);
  const [generation, setGeneration] = useState(0);
  const key = useMemo(() => rememberKey(`${operation}:${generation}`), [operation, generation]);
  const [busy, setBusy] = useState(false);
  const [rateLimited, setRateLimited] = useState(false);
  const [result, setResult] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();
  const errorFeedback = useRef<HTMLDivElement>(null);
  const pollId = result && isSyncPending(result) ? field(result, "operationId", "commandId") : "";

  useEffect(() => {
    // 同一组件切换作业时，旧请求失效；新作业不能继承忙碌状态和旧表单。
    submitting.current = false;
    setBusy(false);
    setRateLimited(false);
    setResult(null);
    setError(undefined);
    setGeneration(0);
    form.resetFields();
    markDirty(false);
    dialog.setBusy(false);
    return () => { requestVersion.current += 1; };
  }, [operation]);

  useEffect(() => {
    const feedback = errorFeedback.current;
    if (!error || !feedback) {
      return;
    }
    // 长表单在底部提交时，拒绝原因必须进入当前弹层视区；不滚动外层工作台。
    feedback.focus({ preventScroll: true });
    const modalBody = feedback.closest<HTMLElement>(".ant-modal-body");
    if (modalBody) {
      modalBody.scrollTop += feedback.getBoundingClientRect().top - modalBody.getBoundingClientRect().top;
    }
  }, [error]);

  useEffect(() => {
    if (!token || !pollId || !pollOperation) {
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
              onDone?.(latest);
              return;
            }
            delay = Math.min(delay * 2, 4000);
            tick();
          })
          .catch((caught) => {
            const status = (caught as { status?: number }).status;
            if (!cancelled && status !== 404 && status !== 403) {
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
  }, [pollId, pollOperation, token]);

  if (!canOperation(workspace, requireScope, resourceType)) {
    return null;
  }

  async function submit(values: Record<string, string>) {
    if (submitting.current) {
      return;
    }
    submitting.current = true;
    const version = requestVersion.current;
    setBusy(true);
    dialog.setBusy(true);
    markDirty(true);
    try {
      const body = asRecord(await onRun(key, values));
      if (version !== requestVersion.current) {
        return;
      }
      if (confirmResult && !confirmResult(body)) {
        throw new Error("创建回执不完整，结果待确认。请先检查列表，避免重复创建。");
      }
      setResult(body);
      setError(undefined);
      if (isTerminalSuccess(body) && httpStatusOf(body) < 300) {
        // 完成的操作身份不能在弹层重新挂载后再次被用于创建新单。
        clearKey(`${operation}:${generation}`);
        setGeneration((current) => current + 1);
      }
      markDirty(false);
      dialog.submitted();
      onDone?.(body);
    } catch (caught) {
      if (version !== requestVersion.current) {
        return;
      }
      const status = (caught as { status?: number }).status;
      setError(caught);
      if (status === 429) {
        setRateLimited(true);
        window.setTimeout(() => {
          if (version === requestVersion.current) setRateLimited(false);
        }, 4000);
      }
    } finally {
      if (version === requestVersion.current) {
        submitting.current = false;
        setBusy(false);
        dialog.setBusy(false);
      }
    }
  }

  function startNext() {
    form.resetFields();
    setResult(null);
    setError(undefined);
    markDirty(false);
  }

  const pending = Boolean(result && isSyncPending(result));
  const accepted = Boolean(result && (result.physicalStatus || result.stockSyncStatus || result.operationId || pending));
  const successContent = result && isTerminalSuccess(result) && renderSuccess ? renderSuccess(result, startNext) : null;
  const body = (
    <>
      {embedded ? <h3 style={{ marginTop: 0, fontSize: 14 }}>{title}</h3> : null}
      <p style={{ color: "rgba(0,0,0,0.45)", marginTop: 0 }}>{hint}</p>
      {error ? <div ref={errorFeedback} className="command-error-feedback" tabIndex={-1} role="group" aria-label="提交未完成" aria-live="assertive">
        {errorBanner(error)}
      </div> : null}
      {successContent || (accepted ? (
        <StatusBanner
          kind={pending ? "sync-pending" : "accepted"}
          title={field(result ?? {}, "physicalStatus") || "命令已受理"}
          operationId={field(result ?? {}, "operationId", "commandId", "taskId")}
          detail={field(result ?? {}, "stockSyncStatus") ? `stockSyncStatus=${field(result ?? {}, "stockSyncStatus")}` : undefined}
        />
      ) : result ? (
        <StatusBanner kind="success" title="已返回最新记录" detail={field(result, "status", "state")} />
      ) : null)}
      {!successContent ? <Form
        form={form}
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
          disabled={disabled || rateLimited || busy}
        >
          {submitLabel}
        </Button>
      </Form> : null}
    </>
  );
  return embedded ? <div>{body}</div> : <Card title={title} size="small">{body}</Card>;
}
