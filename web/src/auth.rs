use crate::{
    App, ORIGIN,
    model::{Error, Result},
};
use axum::http::HeaderMap;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

pub fn digest(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
pub fn random() -> String {
    URL_SAFE_NO_PAD.encode(rand::random::<[u8; 32]>())
}
pub fn header<'a>(h: &'a HeaderMap, key: &str) -> Option<&'a str> {
    h.get(key).and_then(|v| v.to_str().ok())
}
pub fn equal(a: &str, b: &str) -> bool {
    let mut mac =
        Hmac::<Sha256>::new_from_slice(b"noqui-constant-time-comparison").expect("valid HMAC key");
    mac.update(a.as_bytes());
    let tag = mac.finalize().into_bytes();
    let mut other =
        Hmac::<Sha256>::new_from_slice(b"noqui-constant-time-comparison").expect("valid HMAC key");
    other.update(b.as_bytes());
    other.verify_slice(&tag).is_ok()
}
pub fn signature(
    secret: &[u8],
    method: &str,
    path: &str,
    timestamp: &str,
    nonce: &str,
    body: &[u8],
) -> String {
    let text = format!("{method}\n{path}\n{timestamp}\n{nonce}\n{}", digest(body));
    let mut mac = Hmac::<Sha256>::new_from_slice(secret).expect("valid HMAC key");
    mac.update(text.as_bytes());
    format!("v1={:x}", mac.finalize().into_bytes())
}
pub fn internal(
    app: &App,
    method: &str,
    path: &str,
    headers: &HeaderMap,
    body: &[u8],
) -> Result<()> {
    let fail = || Error::new(401, "AUTH_REQUIRED");
    if path.contains('?') || body.len() > 65536 || header(headers, "host") != Some("127.0.0.1:8788")
    {
        return Err(fail());
    }
    if header(headers, "x-noqui-key-id") != Some(app.config.key_id.as_str()) {
        return Err(fail());
    }
    let timestamp = header(headers, "x-noqui-timestamp").ok_or_else(fail)?;
    if timestamp.len() > 12 || !timestamp.bytes().all(|b| b.is_ascii_digit()) {
        return Err(fail());
    }
    let secs: i64 = timestamp.parse().map_err(|_| fail())?;
    if secs < 0 || (app.now() / 1000 - secs).unsigned_abs() > 30 {
        return Err(fail());
    }
    let nonce = header(headers, "x-noqui-nonce").ok_or_else(fail)?;
    if nonce.len() > 128 || URL_SAFE_NO_PAD.decode(nonce).map_or(true, |b| b.len() < 16) {
        return Err(fail());
    }
    let supplied = header(headers, "x-noqui-signature").ok_or_else(fail)?;
    if !equal(
        supplied,
        &signature(&app.config.secret, method, path, timestamp, nonce, body),
    ) {
        return Err(fail());
    }
    let now = app.now();
    let mut inner = app.inner.lock().unwrap();
    inner.nonces.retain(|_, until| *until > now);
    if inner.nonces.contains_key(nonce) {
        return Err(fail());
    }
    if inner.nonces.len() >= 8192 {
        return Err(Error::retry(429, "RATE_LIMITED", 1000));
    }
    inner.nonces.insert(nonce.to_owned(), now + 120000);
    Ok(())
}
pub fn origin(headers: &HeaderMap) -> Result<()> {
    if header(headers, "origin") != Some(ORIGIN) {
        return Err(Error::new(403, "ORIGIN_REJECTED"));
    }
    Ok(())
}
pub fn json_type(headers: &HeaderMap) -> Result<()> {
    if header(headers, "content-type")
        .and_then(|s| s.split(';').next())
        .map(str::trim)
        != Some("application/json")
    {
        return Err(Error::new(400, "INVALID_REQUEST"));
    }
    Ok(())
}
pub fn session_hash(headers: &HeaderMap) -> Result<String> {
    let cookies = header(headers, "cookie").ok_or(Error::new(401, "AUTH_REQUIRED"))?;
    let matches: Vec<_> = cookies
        .split(';')
        .filter_map(|part| part.trim().split_once('='))
        .filter(|(name, _)| *name == "__Host-noqui")
        .collect();
    if matches.len() != 1 || matches[0].1.len() != 43 {
        return Err(Error::new(401, "AUTH_REQUIRED"));
    }
    Ok(digest(matches[0].1.as_bytes()))
}
pub fn csrf(headers: &HeaderMap, expected: &str) -> Result<()> {
    origin(headers)?;
    json_type(headers)?;
    if !equal(header(headers, "x-noqui-csrf").unwrap_or(""), expected) {
        return Err(Error::new(403, "CSRF_REJECTED"));
    }
    Ok(())
}
