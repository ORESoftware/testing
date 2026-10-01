
const sensitiveNames = ["api_key", "apikey", "authorization", "bearer", "cookie", "credential", "email", "jwt", "passphrase", "passwd", "password", "private_key", "pwd", "secret", "session", "signing_key", "token"];
export function isSensitiveName(name) {
  const normalized = name.trim().toLowerCase().replaceAll("-", "_").replaceAll(".", "_");
  return sensitiveNames.some((needle) => normalized.includes(needle));
}
export function redactRecord(input) {
  return Object.fromEntries(Object.entries(input).map(([key, value]) => [key, isSensitiveName(key) ? "[REDACTED]" : value]));
}
