
const sensitiveNames = [
  "api_key",
  "apikey",
  "authorization",
  "bearer",
  "cookie",
  "credential",
  "email",
  "jwt",
  "passphrase",
  "passwd",
  "password",
  "private_key",
  "pwd",
  "secret",
  "session",
  "signing_key",
  "token",
] as const;

export function isSensitiveName(name: string): boolean {
  const normalized = name.trim().toLowerCase().replaceAll("-", "_").replaceAll(".", "_");
  return sensitiveNames.some((needle) => normalized.includes(needle));
}

export function redactRecord(input: Readonly<Record<string, unknown>>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(input).map(([key, value]) => [key, isSensitiveName(key) ? "[REDACTED]" : value]),
  );
}
