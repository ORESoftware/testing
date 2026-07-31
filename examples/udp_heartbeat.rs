use std::{env, net::UdpSocket, time::Duration};

use meta_agent_control_plane::model::{
    AgentEvent, AgentRef, AgentStatus, EventEnvelope, Heartbeat, TransportFrame,
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let address = env::var("META_AGENT_UDP_ADDR").unwrap_or_else(|_| "127.0.0.1:8789".to_owned());
    let token = env::var("META_AGENT_TOKEN").ok();
    let socket = UdpSocket::bind("127.0.0.1:0")?;
    socket.set_read_timeout(Some(Duration::from_secs(2)))?;

    let event = EventEnvelope::new(
        AgentRef {
            agent_id: "rust-example-agent".to_owned(),
            provider: "custom".to_owned(),
            model: "rust-example".to_owned(),
            instance_id: Some("udp-example".to_owned()),
        },
        AgentEvent::Heartbeat(Heartbeat {
            status: Some(AgentStatus::Running),
            active_task_id: Some("task-example".to_owned()),
            load: Some(0.2),
        }),
    );
    let datagram = serde_json::to_vec(&TransportFrame { token, event })?;
    socket.send_to(&datagram, &address)?;

    let mut response = [0_u8; 4_096];
    let (length, _) = socket.recv_from(&mut response)?;
    println!("{}", std::str::from_utf8(&response[..length])?);
    Ok(())
}
