use crate::{App, auth, model::*, state::Session};
use axum::{
    Json, Router,
    body::{Body, Bytes},
    extract::{DefaultBodyLimit, Path, Request, State},
    http::{HeaderMap, HeaderValue, Method, StatusCode},
    middleware::{self, Next},
    response::{
        IntoResponse, Response,
        sse::{Event, Sse},
    },
    routing::{get, post},
};
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use std::{
    convert::Infallible,
    time::{Duration, SystemTime},
};
use tokio::io::{AsyncReadExt, AsyncSeekExt};

impl IntoResponse for Error {
    fn into_response(self) -> Response {
        let mut r = (
            StatusCode::from_u16(self.status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
            Json(self.body()),
        )
            .into_response();
        if self.retry_after > 0 {
            r.headers_mut().insert(
                "retry-after",
                HeaderValue::from_str(&self.retry_after.div_ceil(1000).to_string()).unwrap(),
            );
        }
        r
    }
}
pub fn internal(app: App) -> Router {
    Router::new()
        .route("/internal/v1/media/jobs", post(enqueue))
        .route("/internal/v1/media/jobs/{id}", get(job_status))
        .route("/internal/v1/sync", post(sync))
        .route("/internal/v1/join-tokens", post(token))
        .layer(DefaultBodyLimit::max(65536))
        .layer(middleware::from_fn_with_state(app.clone(), internal_auth))
        .with_state(app)
}
pub fn public(app: App) -> Router {
    Router::new()
        .route("/", get(index))
        .route("/join", get(join))
        .route("/app.js", get(script))
        .route("/join.js", get(join_script))
        .route("/style.css", get(style))
        .route("/healthz", get(|| async { Json(json!({"status":"ok"})) }))
        .route("/api/v1/auth/redeem", post(redeem))
        .route("/api/v1/auth/logout", post(logout))
        .route("/api/v1/me", get(me))
        .route("/api/v1/time", get(time))
        .route("/api/v1/state", get(snapshot))
        .route("/api/v1/events", get(events))
        .route("/api/v1/playback", post(playback))
        .route("/media/{name}", get(media).head(media))
        .layer(DefaultBodyLimit::max(65536))
        .layer(middleware::from_fn(security))
        .with_state(app)
}
async fn security(req: Request, next: Next) -> Response {
    let mut r = next.run(req).await;
    let h = r.headers_mut();
    h.insert("content-security-policy",HeaderValue::from_static("default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' https://textures.minecraft.net; media-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"));
    h.insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    h.insert("referrer-policy", HeaderValue::from_static("no-referrer"));
    h.insert(
        "permissions-policy",
        HeaderValue::from_static("camera=(), microphone=(), geolocation=()"),
    );
    if !h.contains_key("cache-control") {
        h.insert("cache-control", HeaderValue::from_static("no-store"));
    }
    r
}
async fn internal_auth(State(app): State<App>, request: Request, next: Next) -> Response {
    let (parts, body) = request.into_parts();
    let data = match axum::body::to_bytes(body, 65536).await {
        Ok(b) => b,
        Err(_) => return Error::new(413, "BODY_TOO_LARGE").into_response(),
    };
    if let Err(e) = auth::internal(
        &app,
        parts.method.as_str(),
        &parts.uri.to_string(),
        &parts.headers,
        &data,
    ) {
        return e.into_response();
    }
    next.run(Request::from_parts(parts, Body::from(data))).await
}
fn decode<T: DeserializeOwned>(h: &HeaderMap, b: &Bytes) -> Result<T> {
    auth::json_type(h)?;
    serde_json::from_slice(b).map_err(|_| bad())
}
fn authorize(app: &App, h: &HeaderMap) -> Result<(String, Session)> {
    let key = auth::session_hash(h)?;
    let now = app.now();
    let mut state = app.inner.lock().unwrap();
    let session = state.session(&key, now)?;
    let rate = state.rates.entry(key.clone()).or_insert((now, 0));
    if now - rate.0 >= 60000 {
        *rate = (now, 0)
    }
    rate.1 += 1;
    if rate.1 > 240 {
        return Err(Error::retry(429, "RATE_LIMITED", 1000));
    }
    Ok((key, session))
}
async fn enqueue(State(app): State<App>, h: HeaderMap, b: Bytes) -> Result<Response> {
    let r: JobRequest = decode(&h, &b)?;
    let (pending, value) = app.jobs.enqueue(r, app.now()).await?;
    Ok((
        if pending {
            StatusCode::ACCEPTED
        } else {
            StatusCode::OK
        },
        Json(value),
    )
        .into_response())
}
async fn job_status(State(app): State<App>, Path(id): Path<String>) -> Result<Json<Value>> {
    Ok(Json(app.jobs.status(id).await?))
}
async fn sync(State(app): State<App>, h: HeaderMap, b: Bytes) -> Result<Json<Value>> {
    let snapshot: Snapshot = decode(&h, &b)?;
    let ids = snapshot
        .rooms
        .iter()
        .filter_map(|r| r.round.as_ref()?.media_id.clone())
        .collect();
    let media = app.jobs.media(ids).await?;
    let out = app
        .inner
        .lock()
        .unwrap()
        .sync(snapshot, auth::digest(&b), media, app.now())?;
    app.notify();
    Ok(Json(out))
}
async fn token(State(app): State<App>, h: HeaderMap, b: Bytes) -> Result<Response> {
    let request: TokenRequest = decode(&h, &b)?;
    let value = app
        .inner
        .lock()
        .unwrap()
        .register(request, auth::digest(&b), app.now())?;
    Ok((StatusCode::CREATED, Json(value)).into_response())
}
async fn redeem(State(app): State<App>, h: HeaderMap, b: Bytes) -> Result<Response> {
    auth::origin(&h)?;
    let input: Redeem = decode(&h, &b)?;
    let now = app.now();
    let (raw, session) = {
        let mut state = app.inner.lock().unwrap();
        if now - state.redeem_rate.0 >= 60000 {
            state.redeem_rate = (now, 0)
        }
        state.redeem_rate.1 += 1;
        if state.redeem_rate.1 > 120 {
            return Err(Error::retry(429, "RATE_LIMITED", 1000));
        }
        state.redeem(&input.token, now)?
    };
    let mut response = Json(crate::state::State::me(&session)).into_response();
    response.headers_mut().insert(
        "set-cookie",
        HeaderValue::from_str(&format!(
            "__Host-noqui={raw}; Secure; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800"
        ))
        .unwrap(),
    );
    app.notify();
    Ok(response)
}
async fn me(State(app): State<App>, h: HeaderMap) -> Result<Json<Value>> {
    let (_, s) = authorize(&app, &h)?;
    Ok(Json(crate::state::State::me(&s)))
}
async fn time(State(app): State<App>, h: HeaderMap) -> Result<Json<Value>> {
    let received = app.now();
    authorize(&app, &h)?;
    Ok(Json(json!({"receivedAtMs":received,"sentAtMs":app.now()})))
}
async fn snapshot(State(app): State<App>, h: HeaderMap) -> Result<Json<Value>> {
    let (key, _) = authorize(&app, &h)?;
    let value = app.inner.lock().unwrap().public(&key, app.now())?;
    Ok(Json(value))
}
async fn logout(State(app): State<App>, h: HeaderMap, b: Bytes) -> Result<Response> {
    let (key, s) = authorize(&app, &h)?;
    auth::csrf(&h, &s.csrf)?;
    let value: Value = decode(&h, &b)?;
    if value != json!({}) {
        return Err(bad());
    }
    app.inner.lock().unwrap().sessions.remove(&key);
    app.notify();
    let mut r = StatusCode::NO_CONTENT.into_response();
    r.headers_mut().insert(
        "set-cookie",
        HeaderValue::from_static(
            "__Host-noqui=; Secure; HttpOnly; SameSite=Strict; Path=/; Max-Age=0",
        ),
    );
    Ok(r)
}
async fn playback(State(app): State<App>, h: HeaderMap, b: Bytes) -> Result<Json<Value>> {
    let (key, s) = authorize(&app, &h)?;
    auth::csrf(&h, &s.csrf)?;
    let p: Playback = decode(&h, &b)?;
    let value = app.inner.lock().unwrap().playback(&key, p, app.now())?;
    app.notify();
    Ok(Json(value))
}
async fn events(State(app): State<App>, h: HeaderMap) -> Result<Response> {
    let (key, _) = authorize(&app, &h)?;
    let mut changed = app.changed.subscribe();
    let first = app.inner.lock().unwrap().public(&key, app.now())?;
    let stream = async_stream::stream! {
        let id=first["eventId"].as_str().unwrap_or("").to_owned();yield Ok::<Event,Infallible>(Event::default().event("state").id(id).data(first.to_string()));
        let mut timer=tokio::time::interval(Duration::from_secs(5));timer.tick().await;
        loop {
            let state_event=tokio::select!{_ = changed.recv()=>true,_=timer.tick()=>false};
            let value={app.inner.lock().unwrap().public(&key,app.now())};
            match value {
                Ok(value)=>{if state_event {let id=value["eventId"].as_str().unwrap_or("").to_owned();yield Ok(Event::default().event("state").id(id).data(value.to_string()));}else{yield Ok(Event::default().event("lease").data(json!({"serverTimeMs":app.now(),"authorityLeaseExpiresAtMs":value["authorityLeaseExpiresAtMs"],"pluginOnline":true}).to_string()));}},
                Err(_)=>{yield Ok(Event::default().event("revoked").data("{\"code\":\"SESSION_REVOKED\"}"));break;}
            }
        }
    };
    let mut response = Sse::new(stream).into_response();
    response
        .headers_mut()
        .insert("cache-control", HeaderValue::from_static("no-store"));
    response
        .headers_mut()
        .insert("x-accel-buffering", HeaderValue::from_static("no"));
    Ok(response)
}

#[derive(Debug, PartialEq)]
pub enum ByteRange {
    Full,
    Partial(u64, u64),
    Unsatisfiable,
}
pub fn parse_range(value: Option<&str>, length: u64) -> ByteRange {
    let Some(raw) = value.and_then(|s| s.strip_prefix("bytes=")) else {
        return ByteRange::Full;
    };
    if raw.contains(',') || raw.len() > 100 {
        return ByteRange::Full;
    }
    let Some((a, b)) = raw.split_once('-') else {
        return ByteRange::Full;
    };
    if a.is_empty() {
        let Ok(suffix) = b.parse::<u64>() else {
            return ByteRange::Full;
        };
        return if suffix == 0 || length == 0 {
            ByteRange::Unsatisfiable
        } else {
            ByteRange::Partial(length.saturating_sub(suffix), length - 1)
        };
    }
    let Ok(start) = a.parse::<u64>() else {
        return ByteRange::Full;
    };
    let end = if b.is_empty() {
        length.saturating_sub(1)
    } else {
        match b.parse::<u64>() {
            Ok(n) => n,
            Err(_) => return ByteRange::Full,
        }
    };
    if start >= length || end < start {
        return ByteRange::Unsatisfiable;
    }
    ByteRange::Partial(start, end.min(length - 1))
}
struct Download {
    app: App,
    key: String,
}
impl Drop for Download {
    fn drop(&mut self) {
        let mut inner = self.app.inner.lock().unwrap();
        if let Some(count) = inner.downloads.get_mut(&self.key) {
            *count = count.saturating_sub(1);
            if *count == 0 {
                inner.downloads.remove(&self.key);
            }
        }
    }
}
async fn media(
    State(app): State<App>,
    Path(name): Path<String>,
    method: Method,
    h: HeaderMap,
) -> Result<Response> {
    let (key, _) = authorize(&app, &h)?;
    let hash = name
        .strip_suffix(".ogg")
        .filter(|s| hex(s))
        .ok_or(Error::new(404, "MEDIA_NOT_FOUND"))?;
    let m = app
        .inner
        .lock()
        .unwrap()
        .authorize_media(&key, hash, app.now())?;
    let path = app.jobs.root.join("media").join(format!("{hash}.ogg"));
    let metadata = tokio::fs::symlink_metadata(&path)
        .await
        .map_err(|_| Error::new(404, "MEDIA_NOT_FOUND"))?;
    if !metadata.is_file() || metadata.file_type().is_symlink() || metadata.len() != m.byte_length {
        return Err(Error::new(404, "MEDIA_NOT_FOUND"));
    }
    let modified = metadata.modified().unwrap_or(SystemTime::UNIX_EPOCH);
    let last_modified = httpdate::fmt_http_date(modified);
    let tag = format!("\"sha256-{hash}\"");
    let mut response = Response::new(Body::empty());
    let headers = response.headers_mut();
    for (k, v) in [
        ("content-type", "audio/ogg; codecs=opus"),
        ("accept-ranges", "bytes"),
        ("cache-control", "private, max-age=31536000, immutable"),
        ("content-length", &m.byte_length.to_string()),
        ("etag", &tag),
        ("last-modified", &last_modified),
    ] {
        headers.insert(k, HeaderValue::from_str(v).unwrap());
    }
    let not_modified = if let Some(v) = auth::header(&h, "if-none-match") {
        v.split(',')
            .any(|s| s.trim() == "*" || s.trim().trim_start_matches("W/") == tag)
    } else {
        auth::header(&h, "if-modified-since")
            .and_then(|v| httpdate::parse_http_date(v).ok())
            .is_some_and(|since| {
                httpdate::parse_http_date(&last_modified).is_ok_and(|m| m <= since)
            })
    };
    if not_modified {
        *response.status_mut() = StatusCode::NOT_MODIFIED;
        response.headers_mut().remove("content-length");
        return Ok(response);
    }
    if method == Method::HEAD {
        return Ok(response);
    }
    let range_allowed = auth::header(&h, "if-range").is_none_or(|v| {
        v == tag
            || (!v.starts_with('"')
                && httpdate::parse_http_date(v).is_ok_and(|date| {
                    httpdate::parse_http_date(&last_modified).is_ok_and(|m| m <= date)
                }))
    });
    let range = if range_allowed {
        parse_range(auth::header(&h, "range"), m.byte_length)
    } else {
        ByteRange::Full
    };
    let (start, count) = match range {
        ByteRange::Unsatisfiable => {
            *response.status_mut() = StatusCode::RANGE_NOT_SATISFIABLE;
            response.headers_mut().insert(
                "content-range",
                HeaderValue::from_str(&format!("bytes */{}", m.byte_length)).unwrap(),
            );
            response
                .headers_mut()
                .insert("content-length", HeaderValue::from_static("0"));
            return Ok(response);
        }
        ByteRange::Partial(a, b) => {
            *response.status_mut() = StatusCode::PARTIAL_CONTENT;
            response.headers_mut().insert(
                "content-range",
                HeaderValue::from_str(&format!("bytes {a}-{b}/{}", m.byte_length)).unwrap(),
            );
            response.headers_mut().insert(
                "content-length",
                HeaderValue::from_str(&(b - a + 1).to_string()).unwrap(),
            );
            (a, b - a + 1)
        }
        ByteRange::Full => (0, m.byte_length),
    };
    {
        let mut inner = app.inner.lock().unwrap();
        if inner.downloads.values().sum::<usize>() >= 64
            || inner.downloads.get(&key).copied().unwrap_or(0) >= 4
        {
            return Err(Error::retry(429, "RATE_LIMITED", 1000));
        }
        *inner.downloads.entry(key.clone()).or_default() += 1;
    }
    let guard = Download {
        app: app.clone(),
        key,
    };
    let mut file = tokio::fs::File::open(path)
        .await
        .map_err(|_| Error::new(404, "MEDIA_NOT_FOUND"))?;
    file.seek(std::io::SeekFrom::Start(start))
        .await
        .map_err(|_| Error::new(500, "MEDIA_UNAVAILABLE"))?;
    let mut stream = tokio_util::io::ReaderStream::with_capacity(file.take(count), 65536);
    use futures_util::StreamExt;
    let body = async_stream::stream! {let _guard=guard;while let Some(chunk)=stream.next().await{yield chunk;}};
    *response.body_mut() = Body::from_stream(body);
    Ok(response)
}
fn asset(kind: &'static str, text: &'static str) -> Response {
    ([(axum::http::header::CONTENT_TYPE, kind)], text).into_response()
}
async fn index() -> Response {
    asset(
        "text/html; charset=utf-8",
        include_str!("../static/index.html"),
    )
}
async fn join() -> Response {
    asset(
        "text/html; charset=utf-8",
        include_str!("../static/join.html"),
    )
}
async fn script() -> Response {
    asset(
        "text/javascript; charset=utf-8",
        include_str!("../static/app.js"),
    )
}
async fn join_script() -> Response {
    asset(
        "text/javascript; charset=utf-8",
        include_str!("../static/join.js"),
    )
}
async fn style() -> Response {
    asset(
        "text/css; charset=utf-8",
        include_str!("../static/style.css"),
    )
}
