export function EmptyState({ title, detail }: { title: string; detail?: string }) {
  return (
    <div className="empty-state">
      <div className="empty-state-mark" aria-hidden="true">
        <svg viewBox="0 0 48 48">
          <rect x="8" y="12" width="32" height="24" rx="3" fill="none" stroke="currentColor" strokeWidth="1.6" />
          <path d="M8 20h32M18 12v-3h12v3" fill="none" stroke="currentColor" strokeWidth="1.6" />
        </svg>
      </div>
      <strong>{title}</strong>
      {detail ? <p>{detail}</p> : null}
    </div>
  );
}
