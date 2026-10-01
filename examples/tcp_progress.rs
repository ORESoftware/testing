use std::{
    env,
    io::{BufRead, BufReader, Write},
    net::TcpStream,
};

use meta_agent_control_plane::model::{
    AgentEvent, AgentRef, EventEnvelope, ProgressUpdate, TransportFrame,
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let address = env::var("META_AGENT_TCP_ADDR").unwrap_or_else(|_| "127.0.0.1:8788".to_owned());
    let token = env::var("META_AGENT_TOKEN").ok();
    let mut stream = TcpStream::connect(&address)?;

    let event = EventEnvelope::new(
        AgentRef {
            agent_id: "rust-example-agent".to_owned(),
            provider: "custom".to_owned(),
            model: "rust-example".to_owned(),
            instance_id: Some("tcp-example".to_owned()),
        },
        AgentEvent::ProgressUpdated(ProgressUpdate {
            task_id: "task-example".to_owned(),
            progress: 0.5,
            summary: "Reported progress through the provider-neutral TCP protocol.".to_owned(),
            blocker: None,
            next_action: Some("Read and verify the server acknowledgement.".to_owned()),
        }),
    );
    let frame = TransportFrame { token, event };

    serde_json::to_writer(&mut stream, &frame)?;
    stream.write_all(b"\n")?;
    stream.flush()?;

    let mut acknowledgement = String::new();
    BufReader::new(stream).read_line(&mut acknowledgement)?;
    println!("{}", acknowledgement.trim_end());
    Ok(())
}
