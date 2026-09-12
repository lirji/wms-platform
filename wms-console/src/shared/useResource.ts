import { useEffect, useState } from "react";
import { api } from "../api/client";
import { pageItems, type ItemRecord } from "../api/envelope";

export function useResource(token: string | undefined, paths: string[], tick = 0) {
  const [rows, setRows] = useState<ItemRecord[]>([]);
  const [payloads, setPayloads] = useState<unknown[]>([]);
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const usable = paths.filter(Boolean);
  const joined = usable.join("|");

  useEffect(() => {
    if (!token || usable.length === 0) {
      setRows([]);
      setPayloads([]);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    Promise.all(usable.map((path) => api(path, token)))
      .then((bodies) => {
        if (cancelled) {
          return;
        }
        setPayloads(bodies);
        setRows(bodies.flatMap((body) => pageItems(body)));
        setError(undefined);
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
  }, [token, joined, tick]);

  return { rows, payloads, error, loading };
}
