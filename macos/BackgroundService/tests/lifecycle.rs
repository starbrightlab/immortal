use std::{
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};

const READY_TIMEOUT: Duration = Duration::from_secs(3);
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(3);

#[test]
fn managed_process_serves_health_and_stops_cleanly_on_sigterm() {
    let port = free_port();
    let mut child = Command::new(env!("CARGO_BIN_EXE_portal-manager-background"))
        .arg("--port")
        .arg(port.to_string())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("background helper should launch");

    let process_id = child.id();
    let body = wait_for_health(port, &mut child);

    assert!(body.contains("\"ok\":true"), "unexpected body: {body}");
    assert!(
        body.contains("\"service\":\"portal-manager-background\""),
        "unexpected body: {body}"
    );
    assert!(
        body.contains("\"version\":\"1\""),
        "unexpected body: {body}"
    );
    assert!(
        body.contains(&format!("\"pid\":{process_id}")),
        "unexpected body: {body}"
    );
    assert!(
        body.contains("\"startedAtUnixMs\":"),
        "unexpected body: {body}"
    );

    let status = Command::new("kill")
        .arg("-TERM")
        .arg(process_id.to_string())
        .status()
        .expect("kill should be available");
    assert!(status.success(), "SIGTERM delivery failed");

    let deadline = Instant::now() + SHUTDOWN_TIMEOUT;
    while child
        .try_wait()
        .expect("child status should be readable")
        .is_none()
    {
        if Instant::now() >= deadline {
            let _ = child.kill();
            panic!("helper did not stop within {SHUTDOWN_TIMEOUT:?}");
        }
        thread::sleep(Duration::from_millis(20));
    }

    assert!(
        child
            .wait()
            .expect("child status should be readable")
            .success(),
        "SIGTERM shutdown returned a failure status"
    );
}

fn free_port() -> u16 {
    let listener = TcpListener::bind(("127.0.0.1", 0)).expect("loopback port should be available");
    let port = listener
        .local_addr()
        .expect("address should be known")
        .port();
    drop(listener);
    port
}

fn health_body(port: u16) -> Option<String> {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).ok()?;
    stream
        .set_read_timeout(Some(Duration::from_millis(500)))
        .ok()?;
    stream
        .set_write_timeout(Some(Duration::from_millis(500)))
        .ok()?;
    stream
        .write_all(b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        .ok()?;

    let mut response = String::new();
    stream.read_to_string(&mut response).ok()?;
    let body = response.split("\r\n\r\n").nth(1)?.to_owned();
    Some(body)
}

fn wait_for_health(port: u16, child: &mut std::process::Child) -> String {
    let deadline = Instant::now() + READY_TIMEOUT;

    while Instant::now() < deadline {
        if let Some(body) = health_body(port) {
            return body;
        }

        if let Ok(Some(status)) = child.try_wait() {
            panic!("helper exited before readiness: {status}");
        }
        thread::sleep(Duration::from_millis(25));
    }

    panic!("helper did not become ready within {READY_TIMEOUT:?}");
}
