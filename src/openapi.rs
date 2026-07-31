use serde_json::{Value, json};

use crate::{
    daemon::BoundAddresses,
    model::{EVENT_KINDS, UDP_EVENT_KINDS},
};

pub fn document(
    addresses: BoundAddresses,
    ingestion_protected: bool,
    reads_protected: bool,
) -> Value {
    let ingestion_security = if ingestion_protected {
        json!([{ "bearerAuth": [] }])
    } else {
        json!([])
    };
    let read_security = if reads_protected {
        json!([{ "bearerAuth": [] }])
    } else {
        json!([])
    };

    json!({
        "openapi": "3.1.0",
        "info": {
            "title": "Meta-Agent Control Plane API",
            "version": env!("CARGO_PKG_VERSION"),
            "description": "Provider-neutral telemetry for observable agent goals, tasks, progress, explicit reflection, and learned lessons. The protocol intentionally excludes hidden chain-of-thought."
        },
        "servers": [{ "url": "/", "description": "Same-origin daemon API" }],
        "paths": {
            "/api/v1/events": {
                "post": {
                    "summary": "Ingest one agent event",
                    "security": ingestion_security.clone(),
                    "requestBody": {
                        "required": true,
                        "content": {
                            "application/json": {
                                "schema": { "$ref": "#/components/schemas/EventEnvelope" }
                            }
                        }
                    },
                    "responses": {
                        "202": {
                            "description": "Event accepted",
                            "content": {
                                "application/json": {
                                    "schema": { "$ref": "#/components/schemas/IngestAck" }
                                }
                            }
                        },
                        "400": { "description": "Malformed or invalid event" },
                        "401": { "description": "Authentication failed" }
                    }
                }
            },
            "/api/v1/snapshot": {
                "get": {
                    "summary": "Read the current bounded in-memory projection",
                    "security": read_security.clone(),
                    "responses": {
                        "200": {
                            "description": "Current state",
                            "content": {
                                "application/json": {
                                    "schema": { "$ref": "#/components/schemas/Snapshot" }
                                }
                            }
                        },
                        "401": { "description": "Read API authentication failed" }
                    }
                }
            },
            "/ws/agent": {
                "get": {
                    "summary": "WebSocket agent ingestion",
                    "description": "Authenticate during the WebSocket upgrade with a Bearer header or token query parameter, then send EventEnvelope JSON messages.",
                    "security": ingestion_security
                }
            },
            "/ws/ui": {
                "get": {
                    "summary": "Live projection invalidation stream",
                    "description": "Emits an authenticated handshake followed by StoreUpdate messages; clients refetch the snapshot after each update. When read protection is enabled, the first client message must be JSON with a token field."
                }
            },
            "/healthz": {
                "get": {
                    "summary": "Liveness probe",
                    "responses": { "200": { "description": "Alive" } }
                }
            },
            "/readyz": {
                "get": {
                    "summary": "Readiness probe",
                    "responses": { "200": { "description": "Ready" } }
                }
            },
            "/metrics": {
                "get": {
                    "summary": "Prometheus text exposition",
                    "security": read_security,
                    "responses": { "200": { "description": "Metrics" } }
                }
            }
        },
        "components": {
            "securitySchemes": {
                "bearerAuth": {
                    "type": "http",
                    "scheme": "bearer",
                    "bearerFormat": "opaque"
                }
            },
            "schemas": {
                "AgentRef": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["agent_id", "provider", "model"],
                    "properties": {
                        "agent_id": { "type": "string", "minLength": 1, "maxLength": 256 },
                        "provider": { "type": "string", "minLength": 1, "maxLength": 128 },
                        "model": { "type": "string", "minLength": 1, "maxLength": 256 },
                        "instance_id": { "type": ["string", "null"], "maxLength": 256 }
                    }
                },
                "EventEnvelope": {
                    "type": "object",
                    "additionalProperties": true,
                    "required": ["protocol_version", "event_id", "occurred_at", "agent", "kind", "data"],
                    "properties": {
                        "protocol_version": { "const": "v1" },
                        "event_id": { "type": "string", "format": "uuid" },
                        "occurred_at": { "type": "string", "format": "date-time" },
                        "agent": { "$ref": "#/components/schemas/AgentRef" },
                        "session_id": { "type": ["string", "null"] },
                        "correlation_id": { "type": ["string", "null"] },
                        "sequence": { "type": ["integer", "null"], "minimum": 0 },
                        "kind": { "type": "string", "enum": EVENT_KINDS },
                        "data": {
                            "type": "object",
                            "description": "Payload selected by kind. See docs/protocol.md for every event shape."
                        }
                    }
                },
                "TransportFrame": {
                    "type": "object",
                    "required": ["event"],
                    "properties": {
                        "token": { "type": ["string", "null"], "writeOnly": true },
                        "event": { "$ref": "#/components/schemas/EventEnvelope" }
                    }
                },
                "IngestAck": {
                    "type": "object",
                    "required": ["accepted", "duplicate", "event_id", "revision", "transport", "received_at"],
                    "properties": {
                        "accepted": { "type": "boolean" },
                        "duplicate": { "type": "boolean" },
                        "event_id": { "type": "string", "format": "uuid" },
                        "revision": { "type": "integer", "minimum": 0 },
                        "transport": { "type": "string", "enum": ["http", "websocket", "tcp", "udp"] },
                        "received_at": { "type": "string", "format": "date-time" }
                    }
                },
                "Snapshot": {
                    "type": "object",
                    "description": "Bounded LRU projection containing agents, goals, tasks, lessons, recent events, an independent idempotency window, counters, and cache pressure."
                }
            }
        },
        "x-meta-agent-transports": {
            "http": "/api/v1/events",
            "websocket": "/ws/agent",
            "tcp_ndjson": format!("<daemon-host>:{}", addresses.tcp.port()),
            "udp_json": format!("<daemon-host>:{}", addresses.udp.port()),
            "udp_policy": "heartbeat, progress_updated, reflection_recorded, error_observed, and agent_status_changed only",
            "bound_addresses": {
                "http": addresses.http.to_string(),
                "tcp": addresses.tcp.to_string(),
                "udp": addresses.udp.to_string()
            }
        },
        "x-meta-agent-event-kinds": EVENT_KINDS,
        "x-meta-agent-udp-event-kinds": UDP_EVENT_KINDS
    })
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};

    use super::*;

    #[test]
    fn publishes_all_protocol_event_kinds() {
        let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 8787);
        let document = document(
            BoundAddresses {
                http: address,
                tcp: address,
                udp: address,
            },
            true,
            true,
        );
        let kinds = document["x-meta-agent-event-kinds"].as_array().unwrap();
        assert_eq!(kinds.len(), EVENT_KINDS.len());
        assert_eq!(
            document["x-meta-agent-udp-event-kinds"].as_array().map(Vec::len),
            Some(UDP_EVENT_KINDS.len())
        );
        assert_eq!(document["servers"][0]["url"], "/");
        assert!(document["paths"]["/metrics"]["get"]["security"].is_array());
    }
    #[test]
    fn checked_in_openapi_tracks_runtime_protocol_extensions() {
        let checked_in: Value = serde_json::from_str(include_str!("../docs/openapi.json"))
            .expect("checked-in OpenAPI must be valid JSON");
        let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 8787);
        let runtime = document(
            BoundAddresses {
                http: address,
                tcp: address,
                udp: address,
            },
            true,
            true,
        );

        assert_eq!(
            checked_in["x-meta-agent-event-kinds"],
            runtime["x-meta-agent-event-kinds"]
        );
        assert_eq!(
            checked_in["x-meta-agent-udp-event-kinds"],
            runtime["x-meta-agent-udp-event-kinds"]
        );
        assert_eq!(
            checked_in["paths"].as_object().map(|paths| paths.keys().collect::<Vec<_>>()),
            runtime["paths"].as_object().map(|paths| paths.keys().collect::<Vec<_>>())
        );
    }

}
