import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(path, 'utf8');

function enumBody(source, enumName) {
  const match = source.match(new RegExp(`enum\\s+${enumName}\\s*\\{([\\s\\S]*?)\\}`));
  if (!match) {
    throw new Error(`missing enum ${enumName}`);
  }
  return match[1];
}

function rustEnumVariants(source, enumName) {
  const body = enumBody(source, enumName);
  return [...body.matchAll(/^\s*([A-Za-z][A-Za-z0-9_]*)\s*,?\s*$/gm)].map((m) => m[1]);
}

function typeSpecEnumVariants(source, enumName) {
  const body = enumBody(source, enumName);
  return [...body.matchAll(/^\s*([a-z][a-z0-9_]*)\s*,?\s*$/gm)].map((m) => m[1]);
}

function assertEqual(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

const canonicalWireCodecs = ['json', 'messagepack', 'cbor', 'protobuf', 'raw'];
const structuredOperationCodecs = ['Json', 'Messagepack', 'Cbor', 'Protobuf'];

const interfaces = read('sources/interfaces/contracts/rpc-payload/v1/main.tsp');
assertEqual(
  typeSpecEnumVariants(interfaces, 'PayloadCodec'),
  canonicalWireCodecs,
  'ores-interfaces PayloadCodec'
);
assertEqual(
  typeSpecEnumVariants(interfaces, 'TransportFraming'),
  ['http_body', 'tcp_length_delimited', 'websocket_message'],
  'ores-interfaces TransportFraming'
);
if (!/preserve_opaque_bytes:\s*boolean;/.test(interfaces)) {
  throw new Error('ores-interfaces must require preserve_opaque_bytes');
}

const transport = read('sources/transport/src/codec.rs');
assertEqual(
  rustEnumVariants(transport, 'PayloadCodec'),
  ['Json', 'MessagePack', 'Cbor', 'Protobuf', 'Raw'],
  'ores-transport PayloadCodec'
);
for (const [name, id] of [
  ['JSON_WIRE_ID', 1],
  ['MESSAGEPACK_WIRE_ID', 2],
  ['CBOR_WIRE_ID', 3],
  ['PROTOBUF_WIRE_ID', 4],
  ['RAW_WIRE_ID', 5],
]) {
  if (!new RegExp(`const\\s+${name}:\\s*u8\\s*=\\s*${id};`).test(transport)) {
    throw new Error(`ores-transport ${name} must remain ${id}`);
  }
}
for (const [codec, media] of [
  ['Json', 'application/json'],
  ['MessagePack', 'application/msgpack'],
  ['Cbor', 'application/cbor'],
  ['Protobuf', 'application/x-protobuf'],
  ['Raw', 'application/octet-stream'],
]) {
  if (!transport.includes(`Self::${codec} => "${media}"`)) {
    throw new Error(`ores-transport media type drift for ${codec}`);
  }
}
if (!/Self::Ndjson\s*=>\s*matches!\(codec,\s*PayloadCodec::Json\)/.test(transport)) {
  throw new Error('NDJSON must remain JSON-only');
}

const pool = read('sources/rpc-pool/contracts/binary-rpc.tsp');
assertEqual(
  typeSpecEnumVariants(pool, 'PayloadCodec'),
  canonicalWireCodecs,
  'rpc-pool PayloadCodec'
);
assertEqual(
  typeSpecEnumVariants(pool, 'Framing'),
  ['http_body', 'tcp_length_delimited'],
  'rpc-pool Framing'
);

const apiContract = read('sources/api-docs/rust/src/rpc_operation_contract.rs');
assertEqual(
  rustEnumVariants(apiContract, 'RpcPayloadCodec'),
  structuredOperationCodecs,
  'api-docs structured RpcPayloadCodec'
);
if (/enum\s+RpcPayloadCodec[\s\S]*?\bRaw\b[\s\S]*?\}/.test(apiContract)) {
  throw new Error('api-docs RpcPayloadCodec must not collapse opaque raw bytes into structured serializers');
}

const operationSpec = read('sources/api-docs/rust/src/operation_spec.rs');
if (!/enum\s+OperationResponseRepresentation[\s\S]*?\bBinary\b[\s\S]*?\}/.test(operationSpec)) {
  throw new Error('api-docs must retain Binary as the opaque response representation axis');
}

console.log('codec fleet contract: PASS');
