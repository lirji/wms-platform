import { useEffect, useState } from "react";
import { api } from "../api/client";
import { asRecord, type ItemRecord } from "../api/envelope";

export function useDocument(token: string | undefined, path: string | undefined, tick = 0) {
  const [record, setRecord] = useState<ItemRecord>({});
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);

  useEffect(() => {
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
  }, [token, path, tick]);

  return { record, error, loading };
}
