use std::collections::HashMap;
use std::env;
use std::fmt;
use std::io::{self, ErrorKind, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::process;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const SERVICE_NAME: &str = "portal-manager-background";
const SERVICE_VERSION: &str = "1";
const PORT_ENV_VAR: &str = "PORTAL_MANAGER_SERVICE_PORT";
const MAX_CONNECTIONS: usize = 32;
const MAX_HEADER_BYTES: usize = 16 * 1024;

const SIGINT: i32 = 2;
const SIGPIPE: i32 = 13;
const SIGTERM: i32 = 15;
const SHUTDOWN_POLL_INTERVAL: Duration = Duration::from_millis(100);

static SHUTDOWN: AtomicBool = AtomicBool::new(false);

extern "C" {
    fn signal(signal_number: i32, handler: extern "C" fn(i32)) -> usize;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Config {
    port: u16,
}

#[derive(Debug, Eq, PartialEq)]
enum ParsedArgs {
    Help,
    Run(Config),
}

#[derive(Debug)]
enum ArgsError {
    DuplicatePort,
    InvalidPort(String),
    MissingPort,
    MissingPortValue,
    UnknownArgument(String),
}

impl fmt::Display for ArgsError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::DuplicatePort => write!(formatter, "--port specified more than once"),
            Self::InvalidPort(value) => write!(formatter, "invalid port: {value}"),
            Self::MissingPort => write!(formatter, "missing --port or {PORT_ENV_VAR}"),
            Self::MissingPortValue => write!(formatter, "--port requires a value"),
            Self::UnknownArgument(value) => write!(formatter, "unknown argument: {value}"),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum HttpFailure {
    BadRequest,
    RequestTimeout,
    TooLarge,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct Request {
    method: String,
    target: String,
}

struct ConnectionTracker {
    active_count: Mutex<usize>,
    released: Condvar,
}

impl ConnectionTracker {
    fn new() -> Self {
        Self {
            active_count: Mutex::new(0),
            released: Condvar::new(),
        }
    }

    fn acquire(&self) -> bool {
        let mut active_count = self.active_count.lock().expect("connection count poisoned");
        if *active_count >= MAX_CONNECTIONS {
            return false;
        }

        *active_count += 1;
        true
    }

    fn wait_until_idle(&self) {
        let mut active_count = self.active_count.lock().expect("connection count poisoned");
        while *active_count > 0 {
            active_count = self
                .released
                .wait(active_count)
                .expect("connection count poisoned");
        }
    }

    #[cfg(test)]
    fn active_count(&self) -> usize {
        *self.active_count.lock().expect("connection count poisoned")
    }
}

struct ConnectionGuard {
    tracker: Arc<ConnectionTracker>,
}

impl Drop for ConnectionGuard {
    fn drop(&mut self) {
        let mut active_count = self
            .tracker
            .active_count
            .lock()
            .expect("connection count poisoned");
        *active_count -= 1;
        self.tracker.released.notify_all();
    }
}

extern "C" fn signal_handler(_signal_number: i32) {
    SHUTDOWN.store(true, Ordering::Release);
}

extern "C" fn ignore_write_failure(_signal_number: i32) {}

fn valid_port(value: &str) -> Result<u16, ArgsError> {
    value
        .parse::<u16>()
        .ok()
        .filter(|port| *port != 0)
        .ok_or_else(|| ArgsError::InvalidPort(value.to_owned()))
}

fn parse_args(
    arguments: impl IntoIterator<Item = String>,
    environment: &HashMap<String, String>,
) -> Result<ParsedArgs, ArgsError> {
    let mut arguments = arguments.into_iter();
    arguments.next();

    let mut cli_port: Option<u16> = None;
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "-h" | "--help" => return Ok(ParsedArgs::Help),
            "--port" => {
                if cli_port.is_some() {
                    return Err(ArgsError::DuplicatePort);
                }
                let value = arguments.next().ok_or(ArgsError::MissingPortValue)?;
                cli_port = Some(valid_port(&value)?);
            }
            _ => {
                let Some(value) = argument.strip_prefix("--port=") else {
                    return Err(ArgsError::UnknownArgument(argument));
                };
                if cli_port.is_some() {
                    return Err(ArgsError::DuplicatePort);
                }
                cli_port = Some(valid_port(value)?);
            }
        }
    }

    if let Some(port) = cli_port {
        return Ok(ParsedArgs::Run(Config { port }));
    }

    match environment.get(PORT_ENV_VAR) {
        Some(port_text) => Ok(ParsedArgs::Run(Config {
            port: valid_port(port_text)?,
        })),
        None => Err(ArgsError::MissingPort),
    }
}

fn unix_now_ms() -> Result<u64, String> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis().try_into().unwrap_or(u64::MAX))
        .map_err(|error| format!("system clock is before Unix epoch: {error}"))
}

fn health_body(pid: u32, started_at_unix_ms: u64) -> String {
    format!(
        "{{\"ok\":true,\"service\":\"{}\",\"pid\":{},\"version\":\"{}\",\"startedAtUnixMs\":{}}}",
        SERVICE_NAME, pid, SERVICE_VERSION, started_at_unix_ms
    )
}

fn json_response(status_line: &str, body: &str, allow_get_only: bool) -> Vec<u8> {
    let allow_header = if allow_get_only { "Allow: GET\r\n" } else { "" };
    format!(
        "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n{}Connection: close\r\n\r\n{}",
        status_line,
        body.len(),
        allow_header,
        body
    )
    .into_bytes()
}

fn read_request<Stream: Read>(stream: &mut Stream) -> Result<Request, HttpFailure> {
    let mut buffer = Vec::new();
    let mut chunk = [0_u8; 1024];

    loop {
        let count = stream
            .read(&mut chunk)
            .map_err(|error| match error.kind() {
                ErrorKind::WouldBlock | ErrorKind::TimedOut => HttpFailure::RequestTimeout,
                _ => HttpFailure::BadRequest,
            })?;

        if count == 0 {
            return Err(HttpFailure::BadRequest);
        }
        buffer.extend_from_slice(&chunk[..count]);

        if buffer.len() > MAX_HEADER_BYTES {
            return Err(HttpFailure::TooLarge);
        }
        if buffer.windows(4).any(|window| window == b"\r\n\r\n") {
            break;
        }
    }

    let headers = String::from_utf8_lossy(&buffer);
    let request_line = headers.lines().next().unwrap_or_default();
    let parts: Vec<_> = request_line.split_ascii_whitespace().collect();
    if parts.len() != 3 || !parts[2].starts_with("HTTP/") {
        return Err(HttpFailure::BadRequest);
    }

    Ok(Request {
        method: parts[0].to_owned(),
        target: parts[1].to_owned(),
    })
}

fn route(request: Request, started_at_unix_ms: u64) -> Vec<u8> {
    if request.method != "GET" {
        return json_response(
            "405 Method Not Allowed",
            "{\"error\":\"method_not_allowed\"}",
            true,
        );
    }
    if request.target != "/healthz" {
        return json_response("404 Not Found", "{\"error\":\"not_found\"}", false);
    }

    json_response(
        "200 OK",
        &health_body(process::id(), started_at_unix_ms),
        false,
    )
}

fn write_all<Stream: Write>(stream: &mut Stream, response: &[u8]) -> io::Result<()> {
    stream.write_all(response)?;
    stream.flush()
}

fn handle_connection(mut stream: TcpStream, started_at_unix_ms: u64) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));
    let _ = stream.set_nodelay(true);

    let response = match read_request(&mut stream) {
        Ok(request) => route(request, started_at_unix_ms),
        Err(HttpFailure::BadRequest) => {
            json_response("400 Bad Request", "{\"error\":\"bad_request\"}", false)
        }
        Err(HttpFailure::RequestTimeout) => json_response(
            "408 Request Timeout",
            "{\"error\":\"request_timeout\"}",
            false,
        ),
        Err(HttpFailure::TooLarge) => json_response(
            "413 Payload Too Large",
            "{\"error\":\"headers_too_large\"}",
            false,
        ),
    };

    let _ = write_all(&mut stream, &response);
}

fn accept_loop(
    listener: &Arc<TcpListener>,
    tracker: &Arc<ConnectionTracker>,
    started_at_unix_ms: u64,
) -> Result<(), String> {
    while !SHUTDOWN.load(Ordering::Acquire) {
        let (mut stream, _peer_address) = match listener.accept() {
            Ok(connection) => connection,
            Err(error) => {
                if SHUTDOWN.load(Ordering::Acquire) {
                    break;
                }
                if error.kind() == ErrorKind::WouldBlock || error.kind() == ErrorKind::TimedOut {
                    thread::sleep(SHUTDOWN_POLL_INTERVAL);
                    continue;
                }
                return Err(format!("accept failed: {error}"));
            }
        };

        if SHUTDOWN.load(Ordering::Acquire) {
            break;
        }
        if !tracker.acquire() {
            let response = json_response("503 Service Unavailable", "{\"error\":\"busy\"}", false);
            let _ = write_all(&mut stream, &response);
            continue;
        }

        let guard = ConnectionGuard {
            tracker: Arc::clone(tracker),
        };
        let spawn_result = thread::Builder::new()
            .name("portal-manager-connection".to_owned())
            .spawn(move || {
                handle_connection(stream, started_at_unix_ms);
                drop(guard);
            });

        if let Err(error) = spawn_result {
            eprintln!("failed to start connection worker: {error}");
        }
    }

    Ok(())
}

fn run_service(config: Config) -> Result<i32, String> {
    let started_at_unix_ms = unix_now_ms()?;
    let listener = Arc::new(
        TcpListener::bind(("127.0.0.1", config.port))
            .map_err(|error| format!("cannot bind 127.0.0.1:{}: {error}", config.port))?,
    );
    listener
        .set_nonblocking(true)
        .map_err(|error| format!("cannot make listener nonblocking: {error}"))?;

    println!(
        "{} listening on 127.0.0.1:{} pid={}",
        SERVICE_NAME,
        config.port,
        process::id()
    );

    unsafe {
        signal(SIGINT, signal_handler);
        signal(SIGTERM, signal_handler);
        signal(SIGPIPE, ignore_write_failure);
    }

    let tracker = Arc::new(ConnectionTracker::new());
    let started_at_unix_ms_for_accept = started_at_unix_ms;
    let listener_for_accept = Arc::clone(&listener);
    let tracker_for_accept = Arc::clone(&tracker);
    let accept_handle: JoinHandle<Result<(), String>> = thread::Builder::new()
        .name("portal-manager-acceptor".to_owned())
        .spawn(move || {
            accept_loop(
                &listener_for_accept,
                &tracker_for_accept,
                started_at_unix_ms_for_accept,
            )
        })
        .map_err(|error| format!("cannot start acceptor: {error}"))?;

    // The handler sets one atomic flag; the nonblocking acceptor observes it
    // within one poll interval. Idle cost is a single timed sleep, not work.
    loop {
        if SHUTDOWN.load(Ordering::Acquire) {
            break;
        }
        thread::sleep(SHUTDOWN_POLL_INTERVAL);
    }
    SHUTDOWN.store(true, Ordering::Release);

    let accept_result = accept_handle
        .join()
        .map_err(|_| "acceptor worker panicked")?;
    accept_result?;
    tracker.wait_until_idle();

    Ok(0)
}

fn help_text() -> &'static str {
    "Usage: portal-manager-background [--port <port>]\n\
     Uses PORTAL_MANAGER_SERVICE_PORT when --port is absent.\n\
     Serves GET http://127.0.0.1:<port>/healthz only.\n"
}

fn main() {
    let environment: HashMap<String, String> = env::vars().collect();
    match parse_args(env::args(), &environment) {
        Ok(ParsedArgs::Help) => {
            print!("{}", help_text());
            process::exit(0);
        }
        Ok(ParsedArgs::Run(config)) => match run_service(config) {
            Ok(exit_code) => process::exit(exit_code),
            Err(message) => {
                eprintln!("{message}");
                process::exit(1);
            }
        },
        Err(error) => {
            eprintln!("{error}");
            eprintln!("Try --help.");
            process::exit(64);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn arguments<'a>(values: &'a [&'a str]) -> impl IntoIterator<Item = String> + 'a {
        values.iter().map(|value| (*value).to_owned())
    }

    #[test]
    fn parses_explicit_separate_port_over_environment() {
        let mut environment = HashMap::new();
        environment.insert(PORT_ENV_VAR.to_owned(), "9999".to_owned());

        let parsed = parse_args(arguments(&["prog", "--port", "18081"]), &environment)
            .expect("arguments are valid");

        assert_eq!(parsed, ParsedArgs::Run(Config { port: 18081 }));
    }

    #[test]
    fn parses_equals_form_and_environment_fallback() {
        let parsed = parse_args(arguments(&["prog", "--port=18082"]), &HashMap::new())
            .expect("arguments are valid");
        assert_eq!(parsed, ParsedArgs::Run(Config { port: 18082 }));

        let mut environment = HashMap::new();
        environment.insert(PORT_ENV_VAR.to_owned(), "18083".to_owned());
        let parsed = parse_args(arguments(&["prog"]), &environment).expect("environment is valid");
        assert_eq!(parsed, ParsedArgs::Run(Config { port: 18083 }));
    }

    #[test]
    fn rejects_invalid_unknown_duplicate_and_missing_ports() {
        assert_eq!(
            parse_args(arguments(&["prog", "--port", "0"]), &HashMap::new())
                .unwrap_err()
                .to_string(),
            "invalid port: 0"
        );
        assert_eq!(
            parse_args(arguments(&["prog", "--wat"]), &HashMap::new())
                .unwrap_err()
                .to_string(),
            "unknown argument: --wat"
        );
        assert_eq!(
            parse_args(
                arguments(&["prog", "--port=1", "--port=2"]),
                &HashMap::new()
            )
            .unwrap_err()
            .to_string(),
            "--port specified more than once"
        );
        assert_eq!(
            parse_args(arguments(&["prog"]), &HashMap::new())
                .unwrap_err()
                .to_string(),
            format!("missing --port or {PORT_ENV_VAR}")
        );
    }

    #[test]
    fn health_body_matches_contract() {
        assert_eq!(
            health_body(1234, 1_720_000_000_000),
            "{\"ok\":true,\"service\":\"portal-manager-background\",\"pid\":1234,\
              \"version\":\"1\",\"startedAtUnixMs\":1720000000000}"
        );
    }

    #[test]
    fn routes_healthz_methods_and_unknown_paths() {
        let get_healthz = Request {
            method: "GET".to_owned(),
            target: "/healthz".to_owned(),
        };
        assert!(route(get_healthz, 1_720_000_000_000).starts_with(b"HTTP/1.1 200 OK"));

        let post_healthz = Request {
            method: "POST".to_owned(),
            target: "/healthz".to_owned(),
        };
        assert!(
            route(post_healthz, 1_720_000_000_000).starts_with(b"HTTP/1.1 405 Method Not Allowed")
        );

        let unknown_path = Request {
            method: "GET".to_owned(),
            target: "/nope".to_owned(),
        };
        assert!(route(unknown_path, 1_720_000_000_000).starts_with(b"HTTP/1.1 404 Not Found"));
    }

    #[test]
    fn parses_complete_http_request() {
        let mut request_bytes = &b"POST /healthz HTTP/1.1\r\nHost: localhost\r\n\r\n"[..];
        let request = read_request(&mut request_bytes).expect("request parses");
        assert_eq!(request.method, "POST");
        assert_eq!(request.target, "/healthz");
    }

    #[test]
    fn connection_tracker_waits_for_active_request_before_shutdown() {
        let tracker = Arc::new(ConnectionTracker::new());
        let guard = ConnectionGuard {
            tracker: Arc::clone(&tracker),
        };
        assert!(tracker.acquire());
        assert_eq!(tracker.active_count(), 1);

        let drain_waiter = thread::spawn({
            let tracker = Arc::clone(&tracker);
            move || tracker.wait_until_idle()
        });
        thread::sleep(Duration::from_millis(25));
        assert!(
            !drain_waiter.is_finished(),
            "shutdown must wait for the accepted worker"
        );

        drop(guard);

        drain_waiter.join().expect("drain waiter joins");
        assert_eq!(tracker.active_count(), 0);
    }
}
