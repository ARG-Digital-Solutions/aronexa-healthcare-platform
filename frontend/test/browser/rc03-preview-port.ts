/** Approved test-only RC03 ports; never accepts a caller-supplied host. */
export function rc03PreviewPort(raw: string | undefined): number {
  if (raw === undefined) return 5176;
  if (raw !== '5176' && raw !== '5186') throw new Error('RC03 invalid preview port');
  return Number(raw);
}
export function verifyRc03Origin(raw: string, expectedPort: number): URL {
  if (expectedPort !== 5176 && expectedPort !== 5186) throw new Error('RC03 invalid expected port');
  const url = new URL(raw);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1'
      || url.port !== String(expectedPort) || url.pathname !== '/'
      || url.username || url.password || url.search || url.hash)
    throw new Error('RC03 origin does not match configured loopback preview');
  return url;
}
