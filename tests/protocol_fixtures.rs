use meta_agent_control_plane::model::EventEnvelope;

#[test]
fn checked_in_valid_fixtures_match_the_rust_protocol() {
    for source in [
        include_str!("../fixtures/progress-updated.json"),
        include_str!("../fixtures/udp-heartbeat.json"),
        include_str!("../fixtures/lesson-learned.json"),
    ] {
        let event: EventEnvelope = serde_json::from_str(source).expect("fixture must deserialize");
        event.validate().expect("fixture must validate");
    }
}

#[test]
fn checked_in_invalid_fixture_is_rejected_by_domain_validation() {
    let event: EventEnvelope =
        serde_json::from_str(include_str!("../fixtures/invalid-progress.json"))
            .expect("invalid domain fixture is still valid JSON");

    assert!(event.validate().is_err());
}

#[test]
fn udp_fixtures_respect_the_low_authority_transport_policy() {
    let heartbeat: EventEnvelope =
        serde_json::from_str(include_str!("../fixtures/udp-heartbeat.json"))
            .expect("heartbeat fixture must deserialize");
    let lesson: EventEnvelope =
        serde_json::from_str(include_str!("../fixtures/lesson-learned.json"))
            .expect("lesson fixture must deserialize");

    assert!(heartbeat.event.allowed_over_udp());
    assert!(!lesson.event.allowed_over_udp());
}
