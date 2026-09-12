export function hasScope(scopes: string[] | undefined, required?: string | string[]): boolean {
  if (!required) {
    return true;
  }
  const need = Array.isArray(required) ? required : [required];
  const have = scopes ?? [];
  return need.some((scope) => have.includes(scope));
}
