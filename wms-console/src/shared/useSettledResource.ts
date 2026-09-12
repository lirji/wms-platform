import { useEffect, useState } from "react";
import { api } from "../api/client";
import { pageItems, type ItemRecord } from "../api/envelope";

export type SettledBucket = {
  path: string;
  rows: ItemRecord[];
  payload?: unknown;
  error?: unknown;
};

export function useSettledResource(token: string | undefined, paths: string[]) {
  const [buckets, setBuckets] = useState<SettledBucket[]>([]);
  const [loading, setLoading] = useState(true);
  const usable = paths.filter(Boolean);
  const joined = usable.join("|");

  useEffect(() => {
    if (!token || usable.length === 0) {
      setBuckets([]);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    void Promise.all(usable.map(async (path) => {
      try {
        const payload = await api(path, token);
        return { path, payload, rows: pageItems(payload) } satisfies SettledBucket;
      } catch (error) {
        return { path, error, rows: [] } satisfies SettledBucket;
      }
    })).then((next) => {
      if (!cancelled) {
        setBuckets(next);
        setLoading(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [token, joined]);

  return { buckets, loading };
}

export function countText(bucket?: SettledBucket): string {
  if (!bucket) {
    return "—";
  }
  if (bucket.error) {
    return "不可用";
  }
  return String(bucket.rows.length);
}
