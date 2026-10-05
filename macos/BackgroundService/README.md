# Portal Manager Background Service Foundation

A minimal macOS-local daemon foundation for future Portal Manager background work. It is intentionally a standard-library-only Rust binary: no async runtime, no external HTTP framework, and no background polling.

## Build And Run

From this directory:

```sh
cargo build
./target/debug/portal-manager-background --port 1789
```

The port can also come from the environment:

```sh
PORTAL_MANAGER_SERVICE_PORT=1789 ./target/debug/portal-manager-background
```

The CLI form takes precedence over `PORTAL_MANAGER_SERVICE_PORT`. Port `0` is rejected so the selected endpoint remains explicit. Run `cargo test` for argument, routing, request, and health-payload coverage plus a real-process lifecycle check that polls readiness and verifies clean `SIGTERM` shutdown.

## API Contract

- `GET /healthz`
  - Success: `200 OK`
  - Body: `{"ok":true,"service":"portal-manager-background","pid":<pid>,"version":"1","startedAtUnixMs":<milliseconds>}`
  - Header: `Content-Type: application/json`
- `version` identifies the health-contract generation accepted by Portal Manager. `startedAtUnixMs` is captured once before loopback bind and is reported in whole Unix-epoch milliseconds.
- Non-GET methods receive `405 Method Not Allowed` with an `Allow: GET` header and JSON error body.
- Paths other than `/healthz` receive `404 Not Found` with a JSON error body.
- Malformed or oversized requests receive JSON `400 Bad Request` or `413 Payload Too Large` responses.
- Every response closes its connection (`Connection: close`). The implementation deliberately exposes one exact endpoint; it is not intended to become a broad local web API without revisiting authentication and transport boundaries.

The service prints its address and PID once after binding successfully. Bind failures print one concise diagnostic to stderr and exit nonzero.

## Lifecycle And Concurrency

`SIGINT` and `SIGTERM` set one atomic shutdown flag. The listener is nonblocking, so its acceptor observes shutdown within 100 ms without relying on platform-specific interruption of a blocked `accept`; the main loop uses the same bounded wait. After the acceptor exits, Portal Manager waits for active request workers to finish. Idle operation performs no work between these short timed waits.

There is no async runtime. The steady-state process has a main thread, one acceptor thread, and at most 32 short-lived connection-worker threads. Per-request memory is bounded by a 16 KiB header limit. Workers have 10-second read/write socket timeouts, and a condition-variable-backed counter prevents shutdown from finishing while request workers remain active.

## Why This Is LAN-Safe

The listener is bound literally to `127.0.0.1`, not `0.0.0.0`, `[::]`, or an unspecified dual-stack address. The operating system therefore rejects connections originating from another machine before this service performs protocol work. The endpoint is not reachable through ordinary LAN routing, NAT, or Bonjour advertisement, and there is no authentication token that could accidentally make exposure appear harmless.

Keep this property when extending the service. Any future authenticated or broader surface should be a deliberate design change, including threat modeling around local users, proxies, and macOS network entitlements.

## Integration Requirements

Portal Manager should own the service as a separate process rather than sharing mutable state across process boundaries implicitly:

- Choose a stable loopback port outside macOS ephemeral and privileged ranges, and pass it explicitly with `--port` where practical.
- Treat a nonzero startup exit as launch failure; do not retry blindly without checking whether another manager instance owns the port.
- Send `SIGTERM` for normal shutdown. The service finishes accepted in-flight requests before exiting.
- Capture stdout/stderr for diagnostics. Startup emits one listening line; failures emit concise stderr messages.
- If launched by `launchd`, model it as a managed job. If launched temporarily by Portal Manager, reap its exit status and do not leave orphaned copies.
- A GUI embedding or launching the binary must account for macOS code signing, App Sandbox/network-server entitlements, and consistent distribution of the executable. Keep the daemon outside the Xcode project until those lifecycle decisions are made.
- Clients must connect only to `http://127.0.0.1:<port>/healthz`. Never reverse-proxy, forward, or expose this endpoint through a LAN-facing UI.
