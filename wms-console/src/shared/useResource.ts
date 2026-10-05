import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { pageItems, type ItemRecord } from '../api/envelope';
import { canQuery, queryPermissionError } from '../auth/can';
import { useWorkspace } from '../shell/WorkspaceContext';

export function useResource(token: string | undefined, paths: string[], tick = 0) {
  const workspace = useWorkspace();
  const [rows, setRows] = useState<ItemRecord[]>([]);
  const [payloads, setPayloads] = useState<unknown[]>([]);
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const usable = paths.filter(Boolean);
  const joined = usable.join('|');
  const permissions = usable.map((path) => canQuery(workspace, path));
  const permissionKey = permissions.join(',');

  useEffect(() => {
    if (!token || usable.length === 0) {
      setRows([]);
      setPayloads([]);
      // 会话或查询上下文结束时清除旧错误，反馈不能继续指向上一次请求。
      setError(undefined);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setPayloads([]);
    setRows([]);
    setError(undefined);
    setLoading(true);
    Promise.allSettled(
      usable.map((path, index) =>
        permissions[index] ? api(path, token) : Promise.reject(queryPermissionError()),
      ),
    )
      .then((bodies) => {
        if (cancelled) {
          return;
        }
        // 保留原索引，避免企业商品有权、仓库位无权时把商品响应错当库位。
        const payloads = bodies.map((body) =>
          body.status === 'fulfilled' ? body.value : undefined,
        );
        setPayloads(payloads);
        setRows(payloads.flatMap((body) => (body ? pageItems(body) : [])));
        const failure = bodies.find((body) => body.status === 'rejected');
        setError(failure?.status === 'rejected' ? failure.reason : undefined);
      })
      .catch((caught) => {
        if (!cancelled) {
          setError(caught);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [token, joined, permissionKey, tick]);

  return { rows, payloads, error, loading };
}
