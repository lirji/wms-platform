import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { asRecord, type ItemRecord } from '../api/envelope';
import { canQuery, queryPermissionError } from '../auth/can';
import { useWorkspace } from '../shell/WorkspaceContext';

export function useDocument(token: string | undefined, path: string | undefined, tick = 0) {
  const workspace = useWorkspace();
  const allowed = !path || canQuery(workspace, path);
  const [record, setRecord] = useState<ItemRecord>({});
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setRecord({});
    setError(undefined);
    if (!allowed) {
      setError(queryPermissionError());
      setLoading(false);
      return;
    }
    if (!token || !path) {
      setRecord({});
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    api(path, token)
      .then((body) => {
        if (!cancelled) {
          setRecord(asRecord(body));
          setError(undefined);
        }
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
  }, [token, path, allowed, tick]);

  return { record, error, loading };
}
