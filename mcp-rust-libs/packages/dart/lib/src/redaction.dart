
bool isSensitiveName(String name) {
  final normalized = name.trim().toLowerCase().replaceAll(RegExp(r'[-.]'), '_');
  const names = <String>[
    'api_key',
    'apikey',
    'authorization',
    'bearer',
    'cookie',
    'credential',
    'email',
    'jwt',
    'passphrase',
    'passwd',
    'password',
    'private_key',
    'pwd',
    'secret',
    'session',
    'signing_key',
    'token',
  ];
  return names.any(normalized.contains);
}

Map<String, Object?> redactRecord(Map<String, Object?> input) => {
      for (final entry in input.entries) entry.key: isSensitiveName(entry.key) ? '[REDACTED]' : entry.value,
    };
