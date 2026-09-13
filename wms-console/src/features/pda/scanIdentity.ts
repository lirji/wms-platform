/** 扫码只带 SN。当前 ownerEpoch 必须手填，不能默认初值。 */
export function appendIdentityLine(text: string, serial: string, epoch: string): string {
  const trimmedSerial = serial.trim().toLocaleUpperCase("en-US");
  const trimmedEpoch = epoch.trim();
  if (!trimmedSerial) {
    throw new Error("没有扫到序列号");
  }
  if (!/^\d+$/.test(trimmedEpoch)) {
    throw new Error("扫码序列号必须先填当前 ownerEpoch，不能默认代际");
  }
  const line = `${trimmedSerial} ${trimmedEpoch}`;
  return text.trim() ? `${text.trim()}\n${line}` : line;
}
