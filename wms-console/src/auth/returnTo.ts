/** 只允许目标站内单斜杠路径，拒绝协议相对地址。 */
export function sanitizeReturnTo(value: string | null): string {
  if (!value || !value.startsWith("/") || value.startsWith("//")) {
    return "/";
  }
  return value;
}
