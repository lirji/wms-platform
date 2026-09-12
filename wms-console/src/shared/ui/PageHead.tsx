export function PageHead({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        <p className="page-sub">{sub}</p>
      </div>
    </div>
  );
}
