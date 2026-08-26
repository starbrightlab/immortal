// Copyright (c) 2026 Starbright Lab.
//
// This source code is licensed under the MIT license found in the
// LICENSE file in the root directory of this source tree.
//
// fleet — a fast, zero-dependency CLI for the Immortal Fleet Agent.
//
// Drives the in-app Fleet Agent's HTTP API over WiFi (the persistent channel that
// survives reboots, unlike adb-over-WiFi on these non-root Portals), so you can
// manage a wall of Portals without a USB cable or wireless ADB per device.
//
// Rust standard library ONLY — no crates, no Cargo needed. Build it with:
//     rustc -O provisioning/fleet.rs -o provisioning/fleetctl
// (or `cargo build --release` if you prefer; see provisioning/README.md).
//
// Device registry: targets come from the `fleet/<serial>.json` files that
// `./provision.sh --fleet` records ({serial,name,model,ip,agentPort,token}). Set
// $IMMORTAL_FLEET_DIR to point elsewhere, or skip the registry with
// `--host IP --token TOKEN [--port N]`. Choose a target with
// `--device NAME|serial|all` (name match is case-insensitive); a lone registered
// device is used by default.

use std::collections::HashMap;
use std::env;
use std::fs;
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::path::PathBuf;
use std::time::Duration;

const DEFAULT_PORT: u16 = 8723;
// Short connect timeout = fast "device is down" detection; long read timeout so
// slow server-side work (an install can block ~190s) isn't cut off mid-flight.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const IO_TIMEOUT: Duration = Duration::from_secs(210);

const SHA256_K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
    0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
    0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
    0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

fn sha256_transform(state: &mut [u32; 8], block: &[u8]) {
    let mut w = [0u32; 64];
    for (index, word) in block.chunks_exact(4).enumerate() {
        w[index] = u32::from_be_bytes([word[0], word[1], word[2], word[3]]);
    }
    for index in 16..64 {
        let s0 = w[index - 15].rotate_right(7)
            ^ w[index - 15].rotate_right(18)
            ^ (w[index - 15] >> 3);
        let s1 = w[index - 2].rotate_right(17)
            ^ w[index - 2].rotate_right(19)
            ^ (w[index - 2] >> 10);
        w[index] = w[index - 16]
            .wrapping_add(s0)
            .wrapping_add(w[index - 7])
            .wrapping_add(s1);
    }

    let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = *state;
    for (&k, &word) in SHA256_K.iter().zip(&w) {
        let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
        let choice = (e & f) ^ (!e & g);
        let temp1 = h
            .wrapping_add(s1)
            .wrapping_add(choice)
            .wrapping_add(k)
            .wrapping_add(word);
        let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
        let majority = (a & b) ^ (a & c) ^ (b & c);
        let temp2 = s0.wrapping_add(majority);

        h = g;
        g = f;
        f = e;
        e = d.wrapping_add(temp1);
        d = c;
        c = b;
        b = a;
        a = temp1.wrapping_add(temp2);
    }

    state[0] = state[0].wrapping_add(a);
    state[1] = state[1].wrapping_add(b);
    state[2] = state[2].wrapping_add(c);
    state[3] = state[3].wrapping_add(d);
    state[4] = state[4].wrapping_add(e);
    state[5] = state[5].wrapping_add(f);
    state[6] = state[6].wrapping_add(g);
    state[7] = state[7].wrapping_add(h);
}

fn sha256_hex(data: &[u8]) -> String {
    let mut state = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];
    let complete = data.len() / 64 * 64;
    for block in data[..complete].chunks_exact(64) {
        sha256_transform(&mut state, block);
    }

    let remainder = &data[complete..];
    let tail_blocks = if remainder.len() < 56 { 1 } else { 2 };
    let mut tail = vec![0u8; tail_blocks * 64];
    tail[..remainder.len()].copy_from_slice(remainder);
    tail[remainder.len()] = 0x80;
    let bit_len = (data.len() as u64) * 8;
    let tail_len = tail.len();
    tail[tail_len - 8..].copy_from_slice(&bit_len.to_be_bytes());
    for block in tail.chunks_exact(64) {
        sha256_transform(&mut state, block);
    }

    state.iter().map(|word| format!("{:08x}", word)).collect()
}

// ===================== minimal JSON (parse + pretty) =====================

#[derive(Clone)]
enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(Vec<(String, Json)>),
}

struct Parser<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Parser<'a> {
    fn new(b: &'a [u8]) -> Self {
        Parser { b, i: 0 }
    }
    fn ws(&mut self) {
        while self.i < self.b.len() && matches!(self.b[self.i], b' ' | b'\t' | b'\n' | b'\r') {
            self.i += 1;
        }
    }
    fn parse(&mut self) -> Result<Json, String> {
        self.ws();
        let v = self.value()?;
        Ok(v)
    }
    fn value(&mut self) -> Result<Json, String> {
        self.ws();
        if self.i >= self.b.len() {
            return Err("unexpected end".into());
        }
        match self.b[self.i] {
            b'{' => self.object(),
            b'[' => self.array(),
            b'"' => Ok(Json::Str(self.string()?)),
            b't' => self.lit("true", Json::Bool(true)),
            b'f' => self.lit("false", Json::Bool(false)),
            b'n' => self.lit("null", Json::Null),
            _ => self.number(),
        }
    }
    fn lit(&mut self, s: &str, v: Json) -> Result<Json, String> {
        if self.b[self.i..].starts_with(s.as_bytes()) {
            self.i += s.len();
            Ok(v)
        } else {
            Err(format!("invalid literal at {}", self.i))
        }
    }
    fn object(&mut self) -> Result<Json, String> {
        self.i += 1; // {
        let mut out = Vec::new();
        self.ws();
        if self.i < self.b.len() && self.b[self.i] == b'}' {
            self.i += 1;
            return Ok(Json::Obj(out));
        }
        loop {
            self.ws();
            let key = self.string()?;
            self.ws();
            if self.i >= self.b.len() || self.b[self.i] != b':' {
                return Err("expected ':'".into());
            }
            self.i += 1;
            let val = self.value()?;
            out.push((key, val));
            self.ws();
            match self.b.get(self.i) {
                Some(b',') => {
                    self.i += 1;
                }
                Some(b'}') => {
                    self.i += 1;
                    break;
                }
                _ => return Err("expected ',' or '}'".into()),
            }
        }
        Ok(Json::Obj(out))
    }
    fn array(&mut self) -> Result<Json, String> {
        self.i += 1; // [
        let mut out = Vec::new();
        self.ws();
        if self.i < self.b.len() && self.b[self.i] == b']' {
            self.i += 1;
            return Ok(Json::Arr(out));
        }
        loop {
            let val = self.value()?;
            out.push(val);
            self.ws();
            match self.b.get(self.i) {
                Some(b',') => {
                    self.i += 1;
                }
                Some(b']') => {
                    self.i += 1;
                    break;
                }
                _ => return Err("expected ',' or ']'".into()),
            }
        }
        Ok(Json::Arr(out))
    }
    /// Read exactly four hex digits of a `\u` escape and advance past them.
    fn read_hex4(&mut self) -> Result<u32, String> {
        if self.i + 4 > self.b.len() {
            return Err("bad \\u".into());
        }
        let hex = std::str::from_utf8(&self.b[self.i..self.i + 4]).map_err(|_| "bad \\u")?;
        let cp = u32::from_str_radix(hex, 16).map_err(|_| "bad \\u")?;
        self.i += 4;
        Ok(cp)
    }
    fn string(&mut self) -> Result<String, String> {
        if self.b.get(self.i) != Some(&b'"') {
            return Err("expected string".into());
        }
        self.i += 1;
        // Accumulate raw bytes and decode as UTF-8 once at the end. Pushing each input
        // byte as `byte as char` would mis-decode any multi-byte sequence (a byte >= 0x80
        // becomes a Latin-1 codepoint, not its UTF-8 character), so device names / file
        // contents with non-ASCII would come out garbled.
        let mut buf: Vec<u8> = Vec::new();
        let mut tmp = [0u8; 4];
        while self.i < self.b.len() {
            let c = self.b[self.i];
            self.i += 1;
            match c {
                b'"' => return String::from_utf8(buf).map_err(|_| "invalid utf-8 in string".into()),
                b'\\' => {
                    let e = *self.b.get(self.i).ok_or("bad escape")?;
                    self.i += 1;
                    match e {
                        b'"' => buf.push(b'"'),
                        b'\\' => buf.push(b'\\'),
                        b'/' => buf.push(b'/'),
                        b'n' => buf.push(b'\n'),
                        b't' => buf.push(b'\t'),
                        b'r' => buf.push(b'\r'),
                        b'b' => buf.push(0x08),
                        b'f' => buf.push(0x0c),
                        b'u' => {
                            let cp = self.read_hex4()?;
                            // Combine a UTF-16 surrogate pair (e.g. emoji) into one scalar;
                            // a lone/half surrogate becomes U+FFFD.
                            let ch = if (0xD800..=0xDBFF).contains(&cp) {
                                if self.b.get(self.i) == Some(&b'\\') && self.b.get(self.i + 1) == Some(&b'u') {
                                    self.i += 2;
                                    let lo = self.read_hex4()?;
                                    if (0xDC00..=0xDFFF).contains(&lo) {
                                        let scalar = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                                        char::from_u32(scalar).unwrap_or('\u{fffd}')
                                    } else {
                                        '\u{fffd}'
                                    }
                                } else {
                                    '\u{fffd}'
                                }
                            } else {
                                char::from_u32(cp).unwrap_or('\u{fffd}')
                            };
                            buf.extend_from_slice(ch.encode_utf8(&mut tmp).as_bytes());
                        }
                        _ => buf.push(e),
                    }
                }
                _ => {
                    // Raw UTF-8 byte; decoded together with the rest once the string closes.
                    buf.push(c);
                }
            }
        }
        Err("unterminated string".into())
    }
    fn number(&mut self) -> Result<Json, String> {
        let start = self.i;
        while self.i < self.b.len()
            && matches!(self.b[self.i], b'0'..=b'9' | b'-' | b'+' | b'.' | b'e' | b'E')
        {
            self.i += 1;
        }
        let s = std::str::from_utf8(&self.b[start..self.i]).map_err(|_| "bad number")?;
        s.parse::<f64>().map(Json::Num).map_err(|_| "bad number".into())
    }
}

impl Json {
    fn parse(bytes: &[u8]) -> Result<Json, String> {
        Parser::new(bytes).parse()
    }
    fn get<'a>(&'a self, key: &str) -> Option<&'a Json> {
        if let Json::Obj(o) = self {
            o.iter().find(|(k, _)| k == key).map(|(_, v)| v)
        } else {
            None
        }
    }
    fn as_str(&self) -> Option<&str> {
        if let Json::Str(s) = self {
            Some(s)
        } else {
            None
        }
    }
    fn as_i64(&self) -> Option<i64> {
        if let Json::Num(n) = self {
            Some(*n as i64)
        } else {
            None
        }
    }
    fn pretty(&self, indent: usize, out: &mut String) {
        let pad = "  ".repeat(indent);
        let pad1 = "  ".repeat(indent + 1);
        match self {
            Json::Null => out.push_str("null"),
            Json::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
            Json::Num(n) => {
                if n.fract() == 0.0 && n.is_finite() {
                    out.push_str(&format!("{}", *n as i64));
                } else {
                    out.push_str(&format!("{}", n));
                }
            }
            Json::Str(s) => out.push_str(&encode_str(s)),
            Json::Arr(a) => {
                if a.is_empty() {
                    out.push_str("[]");
                    return;
                }
                out.push_str("[\n");
                for (i, v) in a.iter().enumerate() {
                    out.push_str(&pad1);
                    v.pretty(indent + 1, out);
                    if i + 1 < a.len() {
                        out.push(',');
                    }
                    out.push('\n');
                }
                out.push_str(&pad);
                out.push(']');
            }
            Json::Obj(o) => {
                if o.is_empty() {
                    out.push_str("{}");
                    return;
                }
                out.push_str("{\n");
                for (i, (k, v)) in o.iter().enumerate() {
                    out.push_str(&pad1);
                    out.push_str(&encode_str(k));
                    out.push_str(": ");
                    v.pretty(indent + 1, out);
                    if i + 1 < o.len() {
                        out.push(',');
                    }
                    out.push('\n');
                }
                out.push_str(&pad);
                out.push('}');
            }
        }
    }
}

fn encode_str(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            _ => o.push(c),
        }
    }
    o.push('"');
    o
}

// ===================== request-body builders =====================

enum Field {
    B(bool),
    I(i64),
    S(String),
}

fn build_obj(fields: &[(&str, Field)]) -> String {
    let mut o = String::from("{");
    for (i, (k, v)) in fields.iter().enumerate() {
        if i > 0 {
            o.push(',');
        }
        o.push_str(&encode_str(k));
        o.push(':');
        match v {
            Field::B(b) => o.push_str(if *b { "true" } else { "false" }),
            Field::I(n) => o.push_str(&n.to_string()),
            Field::S(s) => o.push_str(&encode_str(s)),
        }
    }
    o.push('}');
    o
}

// ===================== device registry =====================

struct Device {
    name: String,
    host: String,
    token: String,
    port: u16,
    serial: String,
    model: String,
}

impl Device {
    fn base(&self) -> String {
        format!("http://{}:{}", self.host, self.port)
    }
    fn label(&self) -> String {
        if self.serial.is_empty() {
            self.name.clone()
        } else {
            format!("{} / {}", self.name, self.serial)
        }
    }
}

fn registry_dir() -> PathBuf {
    if let Ok(d) = env::var("IMMORTAL_FLEET_DIR") {
        return PathBuf::from(d);
    }
    // Prefer ./fleet (natural when run from provisioning/), then <exe-dir>/fleet.
    let cwd = PathBuf::from("fleet");
    if cwd.is_dir() {
        return cwd;
    }
    if let Ok(exe) = env::current_exe() {
        if let Some(dir) = exe.parent() {
            return dir.join("fleet");
        }
    }
    cwd
}

fn load_registry() -> Vec<Device> {
    let mut out = Vec::new();
    let dir = registry_dir();
    let entries = match fs::read_dir(&dir) {
        Ok(e) => e,
        Err(_) => return out,
    };
    for ent in entries.flatten() {
        let path = ent.path();
        if path.extension().and_then(|s| s.to_str()) != Some("json") {
            continue;
        }
        let text = match fs::read(&path) {
            Ok(t) => t,
            Err(_) => continue,
        };
        let j = match Json::parse(&text) {
            Ok(j) => j,
            Err(e) => {
                eprintln!("warning: skipping {} ({})", path.display(), e);
                continue;
            }
        };
        let token = j.get("token").and_then(|v| v.as_str()).unwrap_or("").to_string();
        if token.is_empty() {
            continue;
        }
        let host = j
            .get("ip")
            .and_then(|v| v.as_str())
            .or_else(|| j.get("host").and_then(|v| v.as_str()))
            .unwrap_or("")
            .to_string();
        let port = j
            .get("agentPort")
            .and_then(|v| v.as_i64())
            .or_else(|| j.get("port").and_then(|v| v.as_i64()))
            .unwrap_or(DEFAULT_PORT as i64) as u16;
        out.push(Device {
            name: j.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            host,
            token,
            port,
            serial: j.get("serial").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            model: j.get("model").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        });
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}

fn resolve_targets(args: &Args) -> Vec<Device> {
    if let Some(host) = args.flag("host") {
        let token = args.flag("token").unwrap_or_else(|| die("--host requires --token"));
        let port = args
            .flag("port")
            .and_then(|p| p.parse().ok())
            .unwrap_or(DEFAULT_PORT);
        return vec![Device {
            name: host.clone(),
            host,
            token,
            port,
            serial: String::new(),
            model: String::new(),
        }];
    }
    let devices = load_registry();
    let sel = args.flag("device");
    match sel.as_deref() {
        Some("all") => {
            if devices.is_empty() {
                die(&format!(
                    "no devices in registry ({}). Run ./provision.sh --fleet, or use --host/--token.",
                    registry_dir().display()
                ));
            }
            devices
        }
        Some(name) => {
            let matches: Vec<Device> = devices
                .into_iter()
                .filter(|d| d.serial == name || d.name.eq_ignore_ascii_case(name))
                .collect();
            if matches.is_empty() {
                die(&format!("no device named/serial {:?}", name));
            }
            matches
        }
        None => {
            if devices.len() == 1 {
                devices
            } else if devices.is_empty() {
                die(&format!(
                    "no devices in registry ({}). Run ./provision.sh --fleet, or use --host/--token.",
                    registry_dir().display()
                ));
            } else {
                let names: Vec<String> = devices.iter().map(|d| d.name.clone()).collect();
                die(&format!(
                    "multiple devices — pass --device NAME|serial|all (known: {})",
                    names.join(", ")
                ));
            }
        }
    }
}

// ===================== HTTP (std only) =====================

fn pct(s: &str) -> String {
    let mut o = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => o.push(b as char),
            _ => o.push_str(&format!("%{:02X}", b)),
        }
    }
    o
}

/// Returns (status, body_bytes). Status 0 means a transport-level error (body holds the message).
fn request(
    dev: &Device,
    method: &str,
    path: &str,
    query: &[(&str, String)],
    body: Option<&[u8]>,
) -> (u16, Vec<u8>) {
    let mut target = String::from(path);
    if !query.is_empty() {
        target.push('?');
        for (i, (k, v)) in query.iter().enumerate() {
            if i > 0 {
                target.push('&');
            }
            target.push_str(k);
            target.push('=');
            target.push_str(&pct(v));
        }
    }

    let addr = match format!("{}:{}", dev.host, dev.port).to_socket_addrs() {
        Ok(mut it) => match it.next() {
            Some(a) => a,
            None => return (0, b"could not resolve host".to_vec()),
        },
        Err(e) => return (0, format!("could not resolve host: {}", e).into_bytes()),
    };
    let mut stream = match TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT) {
        Ok(s) => s,
        Err(e) => return (0, format!("connect failed: {}", e).into_bytes()),
    };
    let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
    let _ = stream.set_write_timeout(Some(IO_TIMEOUT));

    let mut req = format!("{} {} HTTP/1.1\r\n", method, target);
    req.push_str(&format!("Host: {}:{}\r\n", dev.host, dev.port));
    req.push_str(&format!("Authorization: Bearer {}\r\n", dev.token));
    req.push_str("Connection: close\r\n");
    if let Some(b) = body {
        req.push_str("Content-Type: application/octet-stream\r\n");
        req.push_str(&format!("Content-Length: {}\r\n", b.len()));
    }
    req.push_str("\r\n");

    if stream.write_all(req.as_bytes()).is_err() {
        return (0, b"write failed".to_vec());
    }
    if let Some(b) = body {
        if stream.write_all(b).is_err() {
            return (0, b"write failed".to_vec());
        }
    }
    let _ = stream.flush();

    let mut raw = Vec::new();
    if let Err(e) = stream.read_to_end(&mut raw) {
        // A partial read can still hold a complete response if the peer closed; only
        // bail when we got nothing.
        if raw.is_empty() {
            return (0, format!("read failed: {}", e).into_bytes());
        }
    }

    // Split headers / body on the first blank line.
    let sep = raw.windows(4).position(|w| w == b"\r\n\r\n");
    let (head, bdy) = match sep {
        Some(p) => (&raw[..p], raw[p + 4..].to_vec()),
        None => (&raw[..], Vec::new()),
    };
    let status = std::str::from_utf8(head)
        .ok()
        .and_then(|h| h.lines().next())
        .and_then(|line| line.split_whitespace().nth(1))
        .and_then(|code| code.parse::<u16>().ok())
        .unwrap_or(0);
    (status, bdy)
}

// Call + print a (likely JSON) response prettily. Returns process exit code.
fn show(status: u16, body: Vec<u8>) -> i32 {
    let text = String::from_utf8_lossy(&body);
    if let Ok(j) = Json::parse(&body) {
        let mut s = String::new();
        j.pretty(0, &mut s);
        println!("{}", s);
    } else {
        println!("{}", text.trim_end());
    }
    if (200..300).contains(&status) {
        0
    } else {
        if status == 0 {
            eprintln!("(transport error)");
        }
        1
    }
}

fn fanout<F: Fn(&Device) -> i32>(targets: &[Device], f: F) -> i32 {
    let mut rc = 0;
    let multi = targets.len() > 1;
    for d in targets {
        if multi {
            println!("\n=== {} ({}) ===", d.label(), d.base());
        }
        let r = f(d);
        if r != 0 {
            rc = r;
        }
    }
    rc
}

// ===================== arg parsing =====================

struct Args {
    cmd: String,
    positionals: Vec<String>,
    flags: HashMap<String, String>,
    sets: Vec<String>, // repeated --set key=value
}

impl Args {
    fn flag(&self, name: &str) -> Option<String> {
        self.flags.get(name).cloned()
    }
    fn has(&self, name: &str) -> bool {
        self.flags.contains_key(name)
    }
    fn pos(&self, i: usize) -> Option<&String> {
        self.positionals.get(i)
    }
}

// Flags that don't take a value.
const VALUELESS: &[&str] = &["all", "check", "help", "no-pause"];

fn parse_args(argv: &[String]) -> Args {
    let mut cmd = String::new();
    let mut positionals = Vec::new();
    let mut flags = HashMap::new();
    let mut sets = Vec::new();
    let mut i = 0;
    while i < argv.len() {
        let a = &argv[i];
        if a == "-h" || a == "--help" {
            flags.insert("help".into(), "true".into());
            i += 1;
            continue;
        }
        if let Some(rest) = a.strip_prefix("--") {
            let (key, inline_val) = match rest.split_once('=') {
                Some((k, v)) => (k.to_string(), Some(v.to_string())),
                None => (rest.to_string(), None),
            };
            if VALUELESS.contains(&key.as_str()) {
                flags.insert(key, "true".into());
                i += 1;
                continue;
            }
            let val = if let Some(v) = inline_val {
                v
            } else {
                i += 1;
                argv.get(i).cloned().unwrap_or_default()
            };
            if key == "set" {
                sets.push(val);
            } else {
                flags.insert(key, val);
            }
            i += 1;
        } else if cmd.is_empty() {
            cmd = a.clone();
            i += 1;
        } else {
            positionals.push(a.clone());
            i += 1;
        }
    }
    Args { cmd, positionals, flags, sets }
}

fn die(msg: &str) -> ! {
    eprintln!("error: {}", msg);
    std::process::exit(2);
}

fn parse_bool(v: &str) -> bool {
    match v.trim().to_ascii_lowercase().as_str() {
        "1" | "true" | "yes" | "on" | "y" => true,
        "0" | "false" | "no" | "off" | "n" => false,
        _ => die(&format!("expected true/false (got {:?})", v)),
    }
}

fn hhmm_to_min(v: &str) -> i64 {
    let parts: Vec<&str> = v.split(':').collect();
    if parts.len() == 2 {
        if let (Ok(h), Ok(m)) = (parts[0].parse::<i64>(), parts[1].parse::<i64>()) {
            return (h.rem_euclid(24)) * 60 + m.rem_euclid(60);
        }
    }
    die(&format!("expected HH:MM (got {:?})", v));
}

// ===================== commands =====================

fn cmd_devices() -> i32 {
    let devices = load_registry();
    if devices.is_empty() {
        println!(
            "No devices in {}. Run ./provision.sh --fleet on a connected Portal.",
            registry_dir().display()
        );
        return 0;
    }
    for d in &devices {
        let host = if d.host.is_empty() { "(no ip)" } else { &d.host };
        println!("{:<22} {:<16} {}:{}  {}", d.name, d.serial, host, d.port, d.model);
    }
    0
}

fn simple_get(args: &Args, path: &'static str) -> i32 {
    let targets = resolve_targets(args);
    fanout(&targets, |d| {
        let (s, b) = request(d, "GET", path, &[], None);
        show(s, b)
    })
}

fn post_json(args: &Args, path: &'static str, body: String) -> i32 {
    let targets = resolve_targets(args);
    fanout(&targets, |d| {
        let (s, b) = request(d, "POST", path, &[], Some(body.as_bytes()));
        show(s, b)
    })
}

fn cmd_install(args: &Args) -> i32 {
    let pkg = args.pos(0).unwrap_or_else(|| die("install: package name required"));
    let body = if let Some(url) = args.flag("apk-url") {
        build_obj(&[("packageName", Field::S(pkg.clone())), ("apkUrl", Field::S(url))])
    } else {
        build_obj(&[("packageName", Field::S(pkg.clone()))])
    };
    post_json(args, "/install", body)
}

fn cmd_update(args: &Args) -> i32 {
    let body = if args.has("check") {
        build_obj(&[("dryRun", Field::B(true))])
    } else if args.has("all") {
        build_obj(&[("all", Field::B(true))])
    } else if let Some(pkg) = args.pos(0) {
        build_obj(&[("packageName", Field::S(pkg.clone()))])
    } else {
        die("update: pass --all, --check, or a package name");
    };
    post_json(args, "/update", body)
}

fn profile_body(action: &str, package_name: &str, apk_url: Option<&str>) -> String {
    let mut fields = vec![
        ("action", Field::S(action.to_string())),
        ("packageName", Field::S(package_name.to_string())),
    ];
    if let Some(url) = apk_url {
        fields.push(("apkUrl", Field::S(url.to_string())));
    }
    build_obj(&fields)
}

fn retry_profile_body(package_name: &str) -> String {
    build_obj(&[
        ("packageName", Field::S(package_name.to_string())),
        ("retry", Field::B(true)),
    ])
}

fn cmd_apps_profile(args: &Args) -> i32 {
    let action = args.pos(0).map(|s| s.as_str()).unwrap_or("get");
    match action {
        "get" => simple_get(args, "/apps/profile"),
        "set" => {
            let pkg = args.pos(1).cloned().unwrap_or_else(|| {
                die("apps-profile set: package name required")
            });
            let desired = args.flag("desired").unwrap_or_else(|| "install".into());
            if desired != "install" && desired != "remove" {
                die("--desired expects install or remove");
            }
            if desired == "remove" && args.has("apk-url") {
                die("apps-profile set --desired remove cannot use --apk-url");
            }
            post_json(
                args,
                "/apps/profile",
                profile_body(&desired, &pkg, args.flag("apk-url").as_deref()),
            )
        }
        "remove" => {
            let pkg = args.pos(1).cloned().unwrap_or_else(|| {
                die("apps-profile remove: package name required")
            });
            let targets = resolve_targets(args);
            fanout(&targets, |d| {
                let (s, b) = request(
                    d,
                    "DELETE",
                    "/apps/profile",
                    &[("packageName", pkg.clone())],
                    None,
                );
                show(s, b)
            })
        }
        "retry" => {
            let pkg = args.pos(1).cloned().unwrap_or_else(|| {
                die("apps-profile retry: package name required")
            });
            post_json(args, "/apps/profile", retry_profile_body(&pkg))
        }
        other => die(&format!(
            "apps-profile: unknown action {:?} (use get|set|remove)",
            other
        )),
    }
}

fn cmd_config(args: &Args) -> i32 {
    let body = if let Some(name) = args.flag("name") {
        build_obj(&[("name", Field::S(name))])
    } else if !args.sets.is_empty() {
        let mut inner = String::from("{");
        for (i, kv) in args.sets.iter().enumerate() {
            let (k, v) = kv.split_once('=').unwrap_or_else(|| die("config set expects key=value"));
            if i > 0 {
                inner.push(',');
            }
            inner.push_str(&encode_str(k));
            inner.push(':');
            inner.push_str(&encode_str(v));
        }
        inner.push('}');
        format!("{{\"set\":{}}}", inner)
    } else {
        String::from("{}")
    };
    post_json(args, "/config", body)
}

fn cmd_calendar(args: &Args) -> i32 {
    let action = args.pos(0).map(|s| s.as_str()).unwrap_or("get");
    if action == "get" {
        return simple_get(args, "/calendar");
    }
    let mut fields: Vec<(&str, Field)> = Vec::new();
    if action == "off" {
        fields.push(("url", Field::S(String::new())));
    } else if action == "disable" {
        // Hide the widget but keep the saved link (the on/off toggle).
        fields.push(("widgetOn", Field::B(false)));
    } else if action == "enable" {
        fields.push(("widgetOn", Field::B(true)));
    } else {
        if let Some(u) = args.flag("url") {
            fields.push(("url", Field::S(u)));
        }
        if let Some(r) = args.flag("range") {
            fields.push(("range", Field::S(r)));
        }
        if let Some(sz) = args.flag("size") {
            let i = match sz.to_ascii_lowercase().as_str() {
                "small" | "0" => 0,
                "medium" | "1" => 1,
                "large" | "2" => 2,
                _ => die("--size expects small|medium|large"),
            };
            fields.push(("size", Field::I(i)));
        }
        if let Some(side) = args.flag("side") {
            let s = side.to_ascii_lowercase();
            if s != "left" && s != "right" {
                die("--side expects left|right");
            }
            fields.push(("side", Field::S(s)));
        }
        if fields.is_empty() {
            die("calendar set: pass --url/--range/--size/--side (or 'calendar enable|disable|off')");
        }
    }
    post_json(args, "/calendar", build_obj(&fields))
}

fn cmd_screensaver(args: &Args) -> i32 {
    let action = args.pos(0).map(|s| s.as_str()).unwrap_or("get");
    if action == "get" {
        return simple_get(args, "/screensaver");
    }
    let mut fields: Vec<(&str, Field)> = Vec::new();
    if let Some(v) = args.flag("enabled") {
        fields.push(("enabled", Field::B(parse_bool(&v))));
    }
    if let Some(v) = args.flag("source") {
        fields.push(("source", Field::S(v)));
    }
    if let Some(v) = args.flag("folder") {
        fields.push(("folderPath", Field::S(v)));
    }
    if let Some(v) = args.flag("album-url") {
        fields.push(("albumUrl", Field::S(v)));
    }
    if let Some(v) = args.flag("album-refresh") {
        fields.push(("albumRefreshMin", Field::I(v.parse().unwrap_or_else(|_| die("--album-refresh expects an integer")))));
    }
    if let Some(v) = args.flag("fit") {
        fields.push(("fit", Field::S(v)));
    }
    if let Some(v) = args.flag("interval") {
        fields.push(("intervalSec", Field::I(v.parse().unwrap_or_else(|_| die("--interval expects an integer")))));
    }
    if let Some(v) = args.flag("shuffle") {
        fields.push(("shuffle", Field::B(parse_bool(&v))));
    }
    if let Some(v) = args.flag("videos") {
        fields.push(("includeVideo", Field::B(parse_bool(&v))));
    }
    if let Some(v) = args.flag("now-playing") {
        fields.push(("showNowPlaying", Field::B(parse_bool(&v))));
    }
    if let Some(v) = args.flag("battery-saver") {
        fields.push(("batterySaver", Field::B(parse_bool(&v))));
    }
    if let Some(v) = args.flag("presence") {
        fields.push(("presenceMode", Field::S(v.to_ascii_uppercase())));
    }
    if let Some(v) = args.flag("idle-min") {
        fields.push(("idleSleepMin", Field::I(v.parse().unwrap_or_else(|_| die("--idle-min expects an integer")))));
    }
    if let Some(v) = args.flag("overnight") {
        fields.push(("overnightEnabled", Field::B(parse_bool(&v))));
    }
    if let Some(v) = args.flag("overnight-start") {
        fields.push(("overnightStartMin", Field::I(hhmm_to_min(&v))));
    }
    if let Some(v) = args.flag("overnight-end") {
        fields.push(("overnightEndMin", Field::I(hhmm_to_min(&v))));
    }
    if fields.is_empty() {
        die("screensaver set: pass at least one field to change (see the README)");
    }
    post_json(args, "/screensaver", build_obj(&fields))
}

fn cmd_ls(args: &Args) -> i32 {
    let path = args.pos(0).cloned().unwrap_or_else(|| "/sdcard".into());
    let targets = resolve_targets(args);
    fanout(&targets, |d| {
        let (s, b) = request(d, "GET", "/fs/list", &[("path", path.clone())], None);
        show(s, b)
    })
}

fn cmd_cat(args: &Args) -> i32 {
    let path = args.pos(0).cloned().unwrap_or_else(|| die("cat: file path required"));
    let targets = resolve_targets(args);
    fanout(&targets, |d| {
        let (s, b) = request(d, "GET", "/fs/read", &[("path", path.clone())], None);
        print!("{}", String::from_utf8_lossy(&b));
        if !b.ends_with(b"\n") {
            println!();
        }
        if (200..300).contains(&s) { 0 } else { 1 }
    })
}

fn cmd_logcat(args: &Args) -> i32 {
    let lines = args.flag("lines").unwrap_or_else(|| "500".into());
    let targets = resolve_targets(args);
    fanout(&targets, |d| {
        let (s, b) = request(d, "GET", "/logcat", &[("lines", lines.clone())], None);
        print!("{}", String::from_utf8_lossy(&b));
        if (200..300).contains(&s) { 0 } else { 1 }
    })
}

fn cmd_pull(args: &Args) -> i32 {
    let remote = args.pos(0).cloned().unwrap_or_else(|| die("pull: remote path required"));
    let local = args.pos(1).cloned();
    let targets = resolve_targets(args);
    if targets.len() > 1 && local.as_deref().map_or(false, |l| l != "-") {
        die("pull to a local file targets one device; use --device NAME (or '-' for stdout)");
    }
    fanout(&targets, |d| {
        let (s, b) = request(d, "GET", "/fs/read", &[("path", remote.clone())], None);
        if !(200..300).contains(&s) {
            eprintln!("pull failed ({}): {}", s, String::from_utf8_lossy(&b));
            return 1;
        }
        match local.as_deref() {
            None | Some("-") => {
                use std::io::stdout;
                let _ = stdout().write_all(&b);
            }
            Some(p) => match fs::write(p, &b) {
                Ok(_) => println!("wrote {} bytes to {}", b.len(), p),
                Err(e) => {
                    eprintln!("cannot write {} ({})", p, e);
                    return 1;
                }
            },
        }
        0
    })
}

fn cmd_push(args: &Args) -> i32 {
    let local = args.pos(0).cloned().unwrap_or_else(|| die("push: local file required"));
    let remote = args.pos(1).cloned().unwrap_or_else(|| die("push: remote path required"));
    let data = fs::read(&local).unwrap_or_else(|e| die(&format!("cannot read {} ({})", local, e)));
    let targets = resolve_targets(args);
    fanout(&targets, |d| {
        let (s, b) = request(d, "POST", "/fs/write", &[("path", remote.clone())], Some(&data));
        show(s, b)
    })
}

fn cmd_action(args: &Args) -> i32 {
    let name = args.pos(0).cloned().unwrap_or_else(|| die("action: name required (reaffirm|identify|reboot)"));
    post_json(args, "/action", build_obj(&[("action", Field::S(name))]))
}

fn cmd_dev(args: &Args) -> i32 {
    let action = args.pos(0).map(|s| s.as_str()).unwrap_or("status");
    match action {
        "status" => simple_get(args, "/dev"),
        "on" => post_json(args, "/dev", build_obj(&[("enabled", Field::B(true))])),
        "off" => post_json(args, "/dev", build_obj(&[("enabled", Field::B(false))])),
        "update" => cmd_dev_update(args),
        other => die(&format!("dev: unknown action {:?} (use status|on|off|update)", other)),
    }
}

// Push a locally-built APK and install it over Immortal — the fast iterate loop.
// By default it first enables dev mode (pauses the official self-updater) so the
// pushed build isn't overwritten; pass --no-pause to skip that.
fn cmd_dev_update(args: &Args) -> i32 {
    let apk = args.pos(1).cloned().unwrap_or_else(|| die("dev update: local APK path required"));
    let pkg = args.flag("package").unwrap_or_else(|| "com.immortal.launcher".into());
    let remote = args
        .flag("path")
        .unwrap_or_else(|| format!("/sdcard/Android/data/{}/files/dev/immortal-dev.apk", pkg));
    let no_pause = args.has("no-pause");
    let data = fs::read(&apk).unwrap_or_else(|e| die(&format!("cannot read {} ({})", apk, e)));
    if let Some(expected) = args.flag("sha256") {
        let expected = expected.trim().to_ascii_lowercase();
        if expected.len() != 64 || !expected.bytes().all(|b| b.is_ascii_hexdigit()) {
            die("--sha256 expects a 64-character hexadecimal digest");
        }
        let actual = sha256_hex(&data);
        if actual != expected {
            die(&format!(
                "APK SHA-256 mismatch: expected {}, actual {}",
                expected, actual
            ));
        }
        println!("APK SHA-256 verified: {}", actual);
    }
    let targets = resolve_targets(args);
    let multi = targets.len() > 1;
    let mut rc = 0;
    for d in &targets {
        if multi {
            println!("\n=== {} ({}) ===", d.label(), d.base());
        }
        // 1. Pause the official self-updater so the dev build sticks.
        if !no_pause {
            let body = build_obj(&[("enabled", Field::B(true))]);
            let (s, _) = request(d, "POST", "/dev", &[], Some(body.as_bytes()));
            if (200..300).contains(&s) {
                println!("dev mode: on (official updates paused)");
            } else {
                eprintln!("warning: could not enable dev mode (status {})", s);
                rc = 1;
            }
        }
        // 2. Push the local APK to the device.
        println!("pushing {} ({} bytes) -> {}", apk, data.len(), remote);
        let (s, b) = request(d, "POST", "/fs/write", &[("path", remote.clone())], Some(&data));
        if !(200..300).contains(&s) {
            eprintln!("push failed (status {}): {}", s, String::from_utf8_lossy(&b));
            rc = 1;
            continue;
        }
        // 3. Install it over the running Immortal (signature must match — see README).
        println!("installing {} ...", pkg);
        let body = build_obj(&[
            ("path", Field::S(remote.clone())),
            ("packageName", Field::S(pkg.clone())),
        ]);
        let (s2, b2) = request(d, "POST", "/install", &[], Some(body.as_bytes()));
        let r = show(s2, b2);
        if r != 0 {
            rc = r;
        }
    }
    rc
}

fn cmd_raw(args: &Args) -> i32 {
    let method = args.pos(0).cloned().unwrap_or_else(|| die("raw: METHOD required"));
    let mut path = args.pos(1).cloned().unwrap_or_else(|| die("raw: path required"));
    if !path.starts_with('/') {
        path = format!("/{}", path);
    }
    let body = args.pos(2).cloned();
    if let Some(ref b) = body {
        if Json::parse(b.as_bytes()).is_err() {
            die("raw body must be valid JSON");
        }
    }
    let targets = resolve_targets(args);
    let method_up = method.to_uppercase();
    fanout(&targets, |d| {
        let (s, b) = request(
            d,
            &method_up,
            &path,
            &[],
            body.as_ref().map(|x| x.as_bytes()),
        );
        show(s, b)
    })
}

const HELP: &str = "fleetctl — manage Immortal Portals over WiFi (Fleet Agent client)

USAGE:
  fleetctl <command> [--device NAME|serial|all] [options]
  fleetctl <command> --host <ip> --token <token> [--port 8723]

COMMANDS:
  devices                       list devices in the local registry (fleet/*.json)
  info                          device identity, version, install/presence state
  apps                          catalog apps and what's installed
  apps-profile <get|set|remove|retry> [pkg]
                                inspect or manage desired app state (--desired
                                install|remove, --apk-url URL)
  diag                          diagnostics snapshot
  install <pkg> [--apk-url URL] install a catalog package (or a direct APK URL)
  update [<pkg>|--all|--check]  update apps (or dry-run available updates)
  config [--name N|--set k=v]   read or push the agent's free-form config
  dev <status|on|off|update>    developer mode + local-build install (see below)
  calendar <get|set|enable|disable|off>
                                screensaver calendar (--url, --range day|3day|week|agenda,
                                --size small|medium|large, --side left|right;
                                enable/disable toggle the widget, off clears the link)
  screensaver <get|set>         photo-frame config (see options below)
  ls [path]                     list a directory (default /sdcard)
  cat <path>                    print a text file from the device
  pull <remote> [local|-]       download a file (default: stdout)
  push <local> <remote>         upload a local file
  logcat [--lines N]            fetch recent logs (default 500)
  action <reaffirm|identify|reboot>
  raw <METHOD> <path> [json]    call any endpoint directly (escape hatch)

SCREENSAVER set options:
  --enabled BOOL --source default --folder PATH --album-url URL
  --album-refresh MIN --fit fill|fit --interval SEC --shuffle BOOL
  --videos BOOL --now-playing BOOL --battery-saver BOOL
  --presence always_on|presence --idle-min N
  --overnight BOOL --overnight-start HH:MM --overnight-end HH:MM

DEV (iterate on Immortal over WiFi):
  dev status                    show dev mode + installed version
  dev on | dev off              pause / resume the official self-updater
  dev update <local.apk> [--sha256 DIGEST]
                                push a local build and install it over Immortal
                                (enables dev mode first unless --no-pause;
                                 --package PKG and --path REMOTE override defaults)
                                --sha256 refuses to push unless the APK matches the digest.
  NOTE: an in-place update is signature-checked by Android — build/sign your local
  APK with the SAME key as the installed Immortal, or the install is rejected.

Register a device first with `./provision.sh --fleet` on a connected Portal.";

fn main() {
    let argv: Vec<String> = env::args().skip(1).collect();
    let args = parse_args(&argv);
    if args.cmd.is_empty() || args.cmd == "help" || (args.has("help") && args.positionals.is_empty() && args.cmd.is_empty()) {
        println!("{}", HELP);
        std::process::exit(if args.cmd.is_empty() { 2 } else { 0 });
    }
    // `fleet <cmd> --help` prints general help too (keeps the binary tiny).
    if args.has("help") {
        println!("{}", HELP);
        std::process::exit(0);
    }
    let rc = match args.cmd.as_str() {
        "devices" => cmd_devices(),
        "info" => simple_get(&args, "/info"),
        "apps" => simple_get(&args, "/apps"),
        "apps-profile" => cmd_apps_profile(&args),
        "diag" => simple_get(&args, "/diag"),
        "install" => cmd_install(&args),
        "update" => cmd_update(&args),
        "config" => cmd_config(&args),
        "dev" => cmd_dev(&args),
        "calendar" => cmd_calendar(&args),
        "screensaver" => cmd_screensaver(&args),
        "ls" => cmd_ls(&args),
        "cat" => cmd_cat(&args),
        "pull" => cmd_pull(&args),
        "push" => cmd_push(&args),
        "logcat" => cmd_logcat(&args),
        "action" => cmd_action(&args),
        "raw" => cmd_raw(&args),
        other => {
            eprintln!("unknown command: {}\n\n{}", other, HELP);
            std::process::exit(2);
        }
    };
    std::process::exit(rc);
}

// ===================== tests =====================

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(s: &str) -> Json {
        Json::parse(s.as_bytes()).expect("valid json")
    }

    #[test]
    fn string_decodes_multibyte_utf8() {
        // Raw (already-UTF-8) bytes must survive verbatim, not be mangled into
        // Latin-1 — this is the bug the byte-buffer rewrite fixes.
        let j = parse("{\"name\":\"Wohnzimmer-Süd 🛋️\"}");
        assert_eq!(j.get("name").and_then(|v| v.as_str()), Some("Wohnzimmer-Süd 🛋️"));
    }

    #[test]
    fn string_decodes_unicode_escapes() {
        // BMP escape and a surrogate-pair escape (😀 = \uD83D\uDE00).
        let j = parse("{\"a\":\"\\u00fc\",\"b\":\"\\uD83D\\uDE00\"}");
        assert_eq!(j.get("a").and_then(|v| v.as_str()), Some("ü"));
        assert_eq!(j.get("b").and_then(|v| v.as_str()), Some("😀"));
    }

    #[test]
    fn string_handles_simple_escapes() {
        let j = parse("{\"s\":\"a\\tb\\nc\\\"d\\\\e\\/f\"}");
        assert_eq!(j.get("s").and_then(|v| v.as_str()), Some("a\tb\nc\"d\\e/f"));
    }

    #[test]
    fn parses_scalars_and_nesting() {
        let j = parse("{\"n\":42,\"f\":1.5,\"ok\":true,\"z\":null,\"arr\":[1,2,3]}");
        assert_eq!(j.get("n").and_then(|v| v.as_i64()), Some(42));
        if let Some(Json::Arr(a)) = j.get("arr") {
            assert_eq!(a.len(), 3);
        } else {
            panic!("arr missing");
        }
    }

    #[test]
    fn pretty_round_trips_unicode() {
        // pretty-print then re-parse must preserve a non-ASCII value exactly.
        let j = parse("{\"name\":\"Café 北京\"}");
        let mut out = String::new();
        j.pretty(0, &mut out);
        let again = parse(&out);
        assert_eq!(again.get("name").and_then(|v| v.as_str()), Some("Café 北京"));
    }

    #[test]
    fn hhmm_parsing() {
        assert_eq!(hhmm_to_min("22:00"), 1320);
        assert_eq!(hhmm_to_min("07:30"), 450);
    }

    #[test]
    fn profile_body_builds_desired_state_contract() {
        assert_eq!(
            profile_body("install", "com.example.app", Some("https://example.invalid/app.apk")),
            "{\"action\":\"install\",\"packageName\":\"com.example.app\",\"apkUrl\":\"https://example.invalid/app.apk\"}"
        );
        assert_eq!(
            profile_body("remove", "com.example.app", None),
            "{\"action\":\"remove\",\"packageName\":\"com.example.app\"}"
        );
    }

    #[test]
    fn retry_profile_body_targets_an_existing_profile() {
        assert_eq!(
            retry_profile_body("com.example.app"),
            "{\"packageName\":\"com.example.app\",\"retry\":true}"
        );
    }

    #[test]
    fn sha256_digest_matches_known_vector() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(
            sha256_hex(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            sha256_hex(&b"a".repeat(55)),
            "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318"
        );
        assert_eq!(
            sha256_hex(&b"a".repeat(56)),
            "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a"
        );
        assert_eq!(
            sha256_hex(&b"a".repeat(64)),
            "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb"
        );
        assert_eq!(
            sha256_hex(&b"a".repeat(119)),
            "31eba51c313a5c08226adf18d4a359cfdfd8d2e816b13f4af952f7ea6584dcfb"
        );
        assert_eq!(
            sha256_hex(&b"a".repeat(120)),
            "2f3d335432c70b580af0e8e1b3674a7c020d683aa5f73aaaedfdc55af904c21c"
        );
    }
}
