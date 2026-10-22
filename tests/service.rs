use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode, header},
};
use http_body_util::BodyExt;
use noqui_web::{
    App, Clock, Config, PROFILE, api, auth,
    jobs::{Jobs, Media},
    model::*,
};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    sync::{
        Arc,
        atomic::{AtomicI64, Ordering},
    },
};
use tower::ServiceExt;

const PLAYER: &str = "10000000-0000-4000-8000-000000000001";
const MEMBER: &str = "20000000-0000-4000-8000-000000000001";
const ROOM: &str = "30000000-0000-4000-8000-000000000001";
const INSTANCE: &str = "40000000-0000-4000-8000-000000000001";
const MEDIA: &str = "50000000-0000-4000-8000-000000000001";
const ROUND: &str = "60000000-0000-4000-8000-000000000001";
const GENERATION: &str = "70000000-0000-4000-8000-000000000001";
const START: i64 = 1_792_600_000_000;
struct TestClock(AtomicI64);
impl Clock for TestClock {
    fn now_ms(&self) -> i64 {
        self.0.load(Ordering::SeqCst)
    }
}
struct Fixture {
    app: App,
    clock: Arc<TestClock>,
    _dir: tempfile::TempDir,
}
impl Fixture {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let clock = Arc::new(TestClock(AtomicI64::new(START)));
        let app = App::open(
            Config {
                secret: [7; 32],
                key_id: "primary".into(),
                data: dir.path().to_owned(),
                yt_dlp: "missing-yt-dlp".into(),
                ffmpeg: "missing-ffmpeg".into(),
                ffprobe: "missing-ffprobe".into(),
                cookies: None,
            },
            clock.clone(),
        )
        .unwrap();
        Self {
            app,
            clock,
            _dir: dir,
        }
    }
    fn snapshot(&self, seq: u64, phase: &str) -> Snapshot {
        serde_json::from_value(json!({"schemaVersion":1,"instanceId":INSTANCE,"seq":seq.to_string(),"sentAtMs":self.app.now(),"roster":[{"uuid":PLAYER,"membershipId":MEMBER,"nickname":"PlayerOne","textureHash":null,"roomId":ROOM}],"rooms":[{"roomId":ROOM,"roomRevision":seq.to_string(),"phase":phase,"round":null,"reveal":null}]})).unwrap()
    }
    fn seed(&self) -> Media {
        let bytes = b"0123456789";
        let hash = auth::digest(bytes);
        std::fs::write(
            self.app.jobs.root.join("media").join(format!("{hash}.ogg")),
            bytes,
        )
        .unwrap();
        let db = rusqlite::Connection::open(self.app.jobs.root.join("media.sqlite")).unwrap();
        db.execute(
            "INSERT INTO media(id,sha256,duration,bytes) VALUES(?,?,?,?)",
            rusqlite::params![MEDIA, hash, 115000, 10],
        )
        .unwrap();
        db.execute(
            "INSERT INTO jobs(id,video,profile,state,media_id,updated) VALUES(?,?,?,'READY',?,?)",
            rusqlite::params![
                uuid::Uuid::new_v4().to_string(),
                "BaW_jenozKc",
                PROFILE,
                MEDIA,
                START
            ],
        )
        .unwrap();
        Media {
            id: MEDIA.into(),
            sha256: hash,
            duration_ms: 115000,
            byte_length: 10,
        }
    }
    fn round(&self, s: &mut Snapshot) {
        s.rooms[0].round = Some(Round {
            round_id: ROUND.into(),
            number: 1,
            revision: s.seq.clone(),
            playback_generation: GENERATION.into(),
            media_id: Some(MEDIA.into()),
            clip_start_ms: 0,
            clip_end_ms: 115000,
            anchor_position_ms: 0,
            anchor_at_ms: self.app.now() + 3000,
            playing: s.rooms[0].phase != "PREPARING",
        });
    }
    fn sync_direct(&self, s: Snapshot, media: Option<Media>) {
        let bytes = serde_json::to_vec(&s).unwrap();
        let map = media
            .map(|m| HashMap::from([(m.id.clone(), m)]))
            .unwrap_or_default();
        self.app
            .inner
            .lock()
            .unwrap()
            .sync(s, auth::digest(&bytes), map, self.app.now())
            .unwrap();
    }
    fn login(&self) -> (String, String) {
        let token = auth::random();
        let r = TokenRequest {
            schema_version: 1,
            request_id: uuid::Uuid::new_v4().to_string(),
            instance_id: INSTANCE.into(),
            uuid: PLAYER.into(),
            membership_id: MEMBER.into(),
            room_id: ROOM.into(),
            token_sha256: auth::digest(token.as_bytes()),
        };
        let mut state = self.app.inner.lock().unwrap();
        state.register(r, "body".into(), self.app.now()).unwrap();
        let (raw, s) = state.redeem(&token, self.app.now()).unwrap();
        (format!("__Host-noqui={raw}"), s.csrf)
    }
    fn signed(&self, method: &str, path: &str, body: &[u8]) -> Request<Body> {
        let timestamp = (self.app.now() / 1000).to_string();
        let nonce = auth::random();
        Request::builder()
            .method(method)
            .uri(path)
            .header("host", "127.0.0.1:8788")
            .header("content-type", "application/json")
            .header("x-noqui-key-id", "primary")
            .header("x-noqui-timestamp", &timestamp)
            .header("x-noqui-nonce", &nonce)
            .header(
                "x-noqui-signature",
                auth::signature(&[7; 32], method, path, &timestamp, &nonce, body),
            )
            .body(Body::from(body.to_vec()))
            .unwrap()
    }
}
async fn call(
    router: Router,
    request: Request<Body>,
) -> (StatusCode, axum::http::HeaderMap, Vec<u8>) {
    let response = router.oneshot(request).await.unwrap();
    let status = response.status();
    let h = response.headers().clone();
    let bytes = response
        .into_body()
        .collect()
        .await
        .unwrap()
        .to_bytes()
        .to_vec();
    (status, h, bytes)
}
fn job(video: &str) -> JobRequest {
    JobRequest {
        schema_version: 1,
        request_id: uuid::Uuid::new_v4().to_string(),
        video_id: video.into(),
    }
}
fn get(path: &str, cookie: &str) -> Request<Body> {
    Request::builder()
        .uri(path)
        .header("cookie", cookie)
        .body(Body::empty())
        .unwrap()
}

#[tokio::test]
async fn signed_bridge_rejects_tamper_nonce_replay_old_timestamp_and_unknown_schema() {
    let f = Fixture::new();
    let router = api::internal(f.app.clone());
    let body = serde_json::to_vec(&f.snapshot(1, "IDLE")).unwrap();
    let request = f.signed("POST", "/internal/v1/sync", &body);
    let (parts, body) = request.into_parts();
    let bytes = body.collect().await.unwrap().to_bytes();
    let replay = Request::from_parts(parts.clone(), Body::from(bytes.clone()));
    assert_eq!(
        call(
            router.clone(),
            Request::from_parts(parts, Body::from(bytes))
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        call(router.clone(), replay).await.0,
        StatusCode::UNAUTHORIZED
    );
    let mut request = f.signed("POST", "/internal/v1/sync", &body_from_snapshot(&f, 2));
    *request.body_mut() = Body::from("{}");
    assert_eq!(
        call(router.clone(), request).await.0,
        StatusCode::UNAUTHORIZED
    );
    let mut request = f.signed("POST", "/internal/v1/sync", &body_from_snapshot(&f, 2));
    request
        .headers_mut()
        .insert("x-noqui-timestamp", "1".parse().unwrap());
    assert_eq!(
        call(router.clone(), request).await.0,
        StatusCode::UNAUTHORIZED
    );
    let mut value = serde_json::to_value(f.snapshot(2, "IDLE")).unwrap();
    value["answer"] = "secret".into();
    let bytes = serde_json::to_vec(&value).unwrap();
    assert_eq!(
        call(router, f.signed("POST", "/internal/v1/sync", &bytes))
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
}
fn body_from_snapshot(f: &Fixture, n: u64) -> Vec<u8> {
    serde_json::to_vec(&f.snapshot(n, "IDLE")).unwrap()
}

#[tokio::test]
async fn auth_flow_uses_secure_cookie_rejects_csrf_and_has_no_ip_identity() {
    let f = Fixture::new();
    f.sync_direct(f.snapshot(1, "IDLE"), None);
    let token = auth::random();
    let bytes=serde_json::to_vec(&json!({"schemaVersion":1,"requestId":uuid::Uuid::new_v4(),"instanceId":INSTANCE,"uuid":PLAYER,"membershipId":MEMBER,"roomId":ROOM,"tokenSha256":auth::digest(token.as_bytes())})).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/join-tokens", &bytes)
        )
        .await
        .0,
        StatusCode::CREATED
    );
    let redeem = |origin: &str| {
        Request::builder()
            .method("POST")
            .uri("/api/v1/auth/redeem")
            .header("origin", origin)
            .header("content-type", "application/json")
            .body(Body::from(json!({"token":token}).to_string()))
            .unwrap()
    };
    assert_eq!(
        call(
            api::public(f.app.clone()),
            redeem("https://noqui.danta.qzz.io.attacker.example")
        )
        .await
        .0,
        StatusCode::FORBIDDEN
    );
    let (status, h, body) = call(api::public(f.app.clone()), redeem(noqui_web::ORIGIN)).await;
    assert_eq!(status, StatusCode::OK);
    let cookie = h[header::SET_COOKIE].to_str().unwrap();
    for attribute in [
        "__Host-noqui=",
        "Secure",
        "HttpOnly",
        "SameSite=Strict",
        "Path=/",
    ] {
        assert!(cookie.contains(attribute));
    }
    let cookie = cookie.split(';').next().unwrap();
    let me: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(me["me"]["uuid"], PLAYER);
    assert_eq!(
        call(api::public(f.app.clone()), redeem(noqui_web::ORIGIN))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    let bad = Request::builder()
        .method("POST")
        .uri("/api/v1/auth/logout")
        .header("cookie", cookie)
        .header("origin", noqui_web::ORIGIN)
        .header("content-type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    assert_eq!(
        call(api::public(f.app.clone()), bad).await.0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        call(api::public(f.app.clone()), get("/api/v1/me", cookie))
            .await
            .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn membership_change_and_authority_expiry_revoke_existing_sessions_and_tokens() {
    let f = Fixture::new();
    f.sync_direct(f.snapshot(1, "IDLE"), None);
    let (cookie, _) = f.login();
    let mut changed = f.snapshot(2, "IDLE");
    changed.roster[0].membership_id = uuid::Uuid::new_v4().to_string();
    f.sync_direct(changed, None);
    assert_eq!(
        call(api::public(f.app.clone()), get("/api/v1/me", &cookie))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    f.sync_direct(f.snapshot(3, "IDLE"), None);
    let (cookie, _) = f.login();
    f.clock.0.store(START + 15001, Ordering::SeqCst);
    assert_eq!(
        call(api::public(f.app.clone()), get("/api/v1/me", &cookie))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
}

#[test]
fn one_time_token_expires_even_while_plugin_keeps_its_lease_alive() {
    let f = Fixture::new();
    f.sync_direct(f.snapshot(1, "IDLE"), None);
    let token = auth::random();
    let request = TokenRequest {
        schema_version: 1,
        request_id: uuid::Uuid::new_v4().to_string(),
        instance_id: INSTANCE.into(),
        uuid: PLAYER.into(),
        membership_id: MEMBER.into(),
        room_id: ROOM.into(),
        token_sha256: auth::digest(token.as_bytes()),
    };
    f.app
        .inner
        .lock()
        .unwrap()
        .register(request, "x".into(), START)
        .unwrap();
    for seq in 2..=6 {
        f.clock.0.store(START + (seq - 1) * 12000, Ordering::SeqCst);
        f.sync_direct(f.snapshot(seq as u64, "IDLE"), None);
    }
    assert_eq!(
        f.app
            .inner
            .lock()
            .unwrap()
            .redeem(&token, f.app.now())
            .err()
            .unwrap()
            .code,
        "JOIN_INVALID"
    );
}

#[tokio::test]
async fn media_has_authenticated_exact_ranges_head_etag_if_range_and_416() {
    let f = Fixture::new();
    let media = f.seed();
    let mut s = f.snapshot(1, "PREPARING");
    f.round(&mut s);
    f.sync_direct(s, Some(media.clone()));
    let (cookie, _) = f.login();
    let path = format!("/media/{}.ogg", media.sha256);
    let (status, h, body) = call(api::public(f.app.clone()), get(&path, &cookie)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(&body, b"0123456789");
    assert_eq!(h[header::CONTENT_LENGTH], "10");
    assert!(!h.contains_key(header::CONTENT_ENCODING));
    let etag = h[header::ETAG].clone();
    for (range, expected, content_range) in [
        ("bytes=2-4", b"234".as_slice(), "bytes 2-4/10"),
        ("bytes=7-", b"789".as_slice(), "bytes 7-9/10"),
        ("bytes=-2", b"89".as_slice(), "bytes 8-9/10"),
        ("bytes=8-999", b"89".as_slice(), "bytes 8-9/10"),
    ] {
        let mut req = get(&path, &cookie);
        req.headers_mut()
            .insert(header::RANGE, range.parse().unwrap());
        let (status, h, body) = call(api::public(f.app.clone()), req).await;
        assert_eq!(status, StatusCode::PARTIAL_CONTENT);
        assert_eq!(body, expected);
        assert_eq!(h[header::CONTENT_RANGE], content_range);
    }
    let mut req = get(&path, &cookie);
    req.headers_mut()
        .insert(header::RANGE, "bytes=10-".parse().unwrap());
    let (status, h, body) = call(api::public(f.app.clone()), req).await;
    assert_eq!(status, StatusCode::RANGE_NOT_SATISFIABLE);
    assert_eq!(h[header::CONTENT_RANGE], "bytes */10");
    assert!(body.is_empty());
    let mut req = get(&path, &cookie);
    *req.method_mut() = axum::http::Method::HEAD;
    req.headers_mut()
        .insert(header::RANGE, "bytes=2-4".parse().unwrap());
    let (status, h, body) = call(api::public(f.app.clone()), req).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(h[header::CONTENT_LENGTH], "10");
    assert!(body.is_empty());
    let mut req = get(&path, &cookie);
    req.headers_mut()
        .insert(header::IF_NONE_MATCH, etag.clone());
    assert_eq!(
        call(api::public(f.app.clone()), req).await.0,
        StatusCode::NOT_MODIFIED
    );
    for (validator, expected) in [
        (etag.to_str().unwrap(), StatusCode::PARTIAL_CONTENT),
        ("\"old\"", StatusCode::OK),
    ] {
        let mut req = get(&path, &cookie);
        req.headers_mut()
            .insert(header::RANGE, "bytes=2-4".parse().unwrap());
        req.headers_mut()
            .insert(header::IF_RANGE, validator.parse().unwrap());
        assert_eq!(call(api::public(f.app.clone()), req).await.0, expected);
    }
    assert_eq!(
        call(api::public(f.app.clone()), get(&path, "")).await.0,
        StatusCode::UNAUTHORIZED
    );
    let wrong = format!("/media/{}.ogg", "a".repeat(64));
    assert_eq!(
        call(api::public(f.app.clone()), get(&wrong, &cookie))
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(f.app.inner.lock().unwrap().downloads.len(), 0);
}

#[tokio::test]
async fn reveal_can_keep_playing_then_idle_and_public_projection_never_leaks_source() {
    let f = Fixture::new();
    let m = f.seed();
    let mut s = f.snapshot(1, "PREPARING");
    f.round(&mut s);
    f.sync_direct(s, Some(m.clone()));
    let (cookie, _) = f.login();
    let (_, _, bytes) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    let safe = String::from_utf8(bytes).unwrap();
    for secret in ["BaW_jenozKc", "youtube", "title", "aliases", "tags"] {
        assert!(!safe.contains(secret));
    }
    let mut reveal = f.snapshot(2, "REVEAL");
    f.round(&mut reveal);
    reveal.rooms[0].reveal = Some(Reveal {
        title: "정답 제목".into(),
        aliases: vec!["별칭".into()],
        tags: vec!["태그".into()],
        youtube_url: "https://www.youtube.com/watch?v=BaW_jenozKc".into(),
    });
    f.sync_direct(reveal, Some(m));
    let (_, _, bytes) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(v["round"]["playing"], true);
    assert_eq!(v["reveal"]["title"], "정답 제목");
    f.sync_direct(f.snapshot(3, "IDLE"), None);
    let (_, _, bytes) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    assert!(v["round"].is_null());
    assert!(v["media"].is_null());
    assert!(v["reveal"].is_null());
}

#[test]
fn timeline_rejects_stale_bounds_and_unready_assets_but_allows_cancel_and_short_audio() {
    let f = Fixture::new();
    let m = f.seed();
    let map = HashMap::from([(m.id.clone(), m.clone())]);
    let mut s = f.snapshot(1, "COUNTDOWN");
    f.round(&mut s);
    assert_eq!(
        noqui_web::state::validate(&s, &HashMap::new(), START)
            .unwrap_err()
            .code,
        "MEDIA_NOT_READY"
    );
    s.rooms[0].round.as_mut().unwrap().clip_end_ms = 180001;
    assert_eq!(
        noqui_web::state::validate(&s, &map, START)
            .unwrap_err()
            .code,
        "INVALID_TIMELINE"
    );
    s.rooms[0].round.as_mut().unwrap().clip_end_ms = 40000;
    assert!(noqui_web::state::validate(&s, &map, START).is_ok());
    f.sync_direct(s, Some(m.clone()));
    let mut changed = f.snapshot(1, "PLAYING");
    f.round(&mut changed);
    assert_eq!(
        f.app
            .inner
            .lock()
            .unwrap()
            .sync(changed, "changed".into(), map, START)
            .unwrap_err()
            .code,
        "REVISION_CONFLICT"
    );
    f.sync_direct(f.snapshot(2, "IDLE"), None);
}

#[tokio::test]
async fn concurrent_media_registration_deduplicates_and_idempotency_conflict_is_explicit() {
    let f = Fixture::new();
    let mut tasks = Vec::new();
    for _ in 0..12 {
        let jobs = f.app.jobs.clone();
        tasks.push(tokio::spawn(async move {
            jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap().1["jobId"]
                .as_str()
                .unwrap()
                .to_owned()
        }));
    }
    let mut ids = std::collections::HashSet::new();
    for task in tasks {
        ids.insert(task.await.unwrap());
    }
    assert_eq!(ids.len(), 1);
    let request_id = uuid::Uuid::new_v4().to_string();
    let mut a = job("BaW_jenozKc");
    a.request_id = request_id.clone();
    f.app.jobs.enqueue(a, START).await.unwrap();
    let mut b = job("AAAAAAAAAAA");
    b.request_id = request_id;
    assert_eq!(
        f.app.jobs.enqueue(b, START).await.unwrap_err().code,
        "IDEMPOTENCY_CONFLICT"
    );
    assert_eq!(
        f.app
            .jobs
            .enqueue(job("http://127.0.0.1"), START)
            .await
            .unwrap_err()
            .code,
        "INVALID_SOURCE"
    );
}

#[tokio::test]
async fn missing_tool_fails_one_async_job_and_cleans_up_without_taking_control_down() {
    let f = Fixture::new();
    let (_, job) = f.app.jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap();
    let id = job["jobId"].as_str().unwrap().to_owned();
    let worker = tokio::spawn(noqui_web::jobs::worker(f.app.clone()));
    let outcome = tokio::time::timeout(std::time::Duration::from_secs(3), async {
        loop {
            let status = f.app.jobs.status(id.clone()).await.unwrap();
            if status["state"] == "FAILED" {
                break status;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    worker.abort();
    let _ = worker.await;
    assert_eq!(outcome["failureCode"], "INGEST_UNAVAILABLE");
    assert_eq!(outcome["retryable"], true);
    assert!(!f.app.jobs.root.join("tmp").join(id).exists());
    assert_eq!(
        call(api::public(f.app.clone()), get("/healthz", ""))
            .await
            .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn ready_source_cache_is_reused_without_a_new_queued_job() {
    let f = Fixture::new();
    let media = f.seed();
    let (pending, result) = f.app.jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap();
    assert!(!pending);
    assert_eq!(result["state"], "READY");
    assert_eq!(result["mediaId"], media.id);
    assert_eq!(result["profileVersion"], "opus-48-clip115-v1");
    assert_eq!(result["durationMs"], 115000);
    assert_eq!(result["mime"], "audio/ogg; codecs=opus");
}

#[tokio::test]
async fn clipped_profile_dedupes_separately_and_cannot_select_legacy_full_length_media() {
    let f = Fixture::new();
    let legacy = f.seed();
    let old_id = {
        let db = rusqlite::Connection::open(f.app.jobs.root.join("media.sqlite")).unwrap();
        db.execute(
            "UPDATE jobs SET profile='opus-48-v1' WHERE video=?",
            ["BaW_jenozKc"],
        )
        .unwrap();
        db.query_row("SELECT id FROM jobs WHERE video=?", ["BaW_jenozKc"], |r| {
            r.get::<_, String>(0)
        })
        .unwrap()
    };
    let old = f.app.jobs.status(old_id.clone()).await.unwrap();
    assert_eq!(old["profileVersion"], "opus-48-v1");
    assert!(f.app.jobs.media(vec![legacy.id]).await.unwrap().is_empty());
    let (pending, new) = f.app.jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap();
    assert!(pending);
    assert_eq!(new["profileVersion"], "opus-48-clip115-v1");
    assert_eq!(new["state"], "QUEUED");
    assert_ne!(new["jobId"], old_id);
    let (_, duplicate) = f.app.jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap();
    assert_eq!(new["jobId"], duplicate["jobId"]);
}

#[tokio::test]
async fn queue_is_bounded_and_failed_jobs_recover_after_explicit_retry_cooldown() {
    let f = Fixture::new();
    for i in 0..20 {
        f.app
            .jobs
            .enqueue(job(&format!("AAAAAAAA{i:03}")), START)
            .await
            .unwrap();
    }
    assert_eq!(
        f.app
            .jobs
            .enqueue(job("ZZZZZZZZZZZ"), START)
            .await
            .unwrap_err()
            .code,
        "QUEUE_FULL"
    );
    let (_, a) = f.app.jobs.enqueue(job("AAAAAAAA000"), START).await.unwrap();
    let id = a["jobId"].as_str().unwrap().to_owned();
    f.app
        .jobs
        .fail(id.clone(), "SOURCE_BLOCKED", true, START)
        .await
        .unwrap();
    let (_, status) = f
        .app
        .jobs
        .enqueue(job("AAAAAAAA000"), START + 100)
        .await
        .unwrap();
    assert_eq!(status["state"], "FAILED");
    let (_, status) = f
        .app
        .jobs
        .enqueue(job("AAAAAAAA000"), START + 30000)
        .await
        .unwrap();
    assert_eq!(status["state"], "QUEUED");
    assert_eq!(status["jobId"], id);
}

#[tokio::test]
async fn restart_marks_interrupted_jobs_failed_and_rejects_missing_ready_files() {
    let f = Fixture::new();
    let (_, j) = f.app.jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap();
    let id = j["jobId"].as_str().unwrap().to_owned();
    let db = rusqlite::Connection::open(f.app.jobs.root.join("media.sqlite")).unwrap();
    db.execute("UPDATE jobs SET state='ENCODING' WHERE id=?", [&id])
        .unwrap();
    drop(db);
    let reopened = Jobs::open(&f.app.config.data, START + 1000).unwrap();
    let s = reopened.status(id).await.unwrap();
    assert_eq!(s["failureCode"], "INGEST_INTERRUPTED");
    assert_eq!(s["retryable"], true);
    let g = Fixture::new();
    let media = g.seed();
    std::fs::remove_file(
        g.app
            .jobs
            .root
            .join("media")
            .join(format!("{}.ogg", media.sha256)),
    )
    .unwrap();
    let reopened = Jobs::open(&g.app.config.data, START + 1000).unwrap();
    assert!(reopened.media(vec![MEDIA.into()]).await.unwrap().is_empty());
}

#[tokio::test]
async fn same_nat_address_does_not_merge_two_verified_minecraft_identities() {
    let f = Fixture::new();
    let mut snapshot = f.snapshot(1, "IDLE");
    let other = uuid::Uuid::new_v4().to_string();
    let membership = uuid::Uuid::new_v4().to_string();
    snapshot.roster.push(Player {
        uuid: other.clone(),
        membership_id: membership.clone(),
        nickname: "OtherPlayer".into(),
        texture_hash: None,
        room_id: ROOM.into(),
    });
    f.sync_direct(snapshot, None);
    let (first, _) = f.login();
    let raw_token = auth::random();
    let request = TokenRequest {
        schema_version: 1,
        request_id: uuid::Uuid::new_v4().to_string(),
        instance_id: INSTANCE.into(),
        uuid: other.clone(),
        membership_id: membership,
        room_id: ROOM.into(),
        token_sha256: auth::digest(raw_token.as_bytes()),
    };
    let second = {
        let mut state = f.app.inner.lock().unwrap();
        state.register(request, "other".into(), START).unwrap();
        format!(
            "__Host-noqui={}",
            state.redeem(&raw_token, START).unwrap().0
        )
    };
    for (cookie, expected) in [(first, PLAYER.to_owned()), (second, other)] {
        let mut request = get("/api/v1/me", &cookie);
        request
            .headers_mut()
            .insert("x-forwarded-for", "203.0.113.9".parse().unwrap());
        let (_, _, body) = call(api::public(f.app.clone()), request).await;
        let me: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(me["me"]["uuid"], expected);
    }
}

#[tokio::test]
async fn unbounded_or_cross_room_control_payloads_fail_without_panicking_or_leaking() {
    let f = Fixture::new();
    let mut s = f.snapshot(1, "IDLE");
    s.sent_at_ms = i64::MIN;
    let bytes = serde_json::to_vec(&s).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &bytes)
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    let mut s = f.snapshot(1, "IDLE");
    s.roster[0].room_id = uuid::Uuid::new_v4().to_string();
    let bytes = serde_json::to_vec(&s).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &bytes)
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    let mut s = f.snapshot(1, "PREPARING");
    s.rooms[0].reveal = Some(Reveal {
        title: "secret answer".into(),
        aliases: vec![],
        tags: vec![],
        youtube_url: "https://www.youtube.com/watch?v=BaW_jenozKc".into(),
    });
    let bytes = serde_json::to_vec(&s).unwrap();
    let (status, _, body) = call(
        api::internal(f.app.clone()),
        f.signed("POST", "/internal/v1/sync", &bytes),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(!String::from_utf8_lossy(&body).contains("secret answer"));
}

#[test]
fn source_and_output_boundaries_reject_live_huge_duration_or_answer_metadata() {
    assert!(noqui_web::jobs::source_duration(&json!({"duration":300})).is_ok());
    for v in [
        json!({"duration":301}),
        json!({"duration":0}),
        json!({"duration":100,"is_live":true}),
        json!({}),
    ] {
        assert!(noqui_web::jobs::source_duration(&v).is_err());
    }
    let good = json!({"format":{"duration":"115.0","format_name":"ogg"},"streams":[{"codec_name":"opus","channels":2,"sample_rate":"48000","tags":{"encoder":"Lavc"}}]});
    assert!(noqui_web::jobs::verify_output(&good, 1000).is_ok());
    assert!(noqui_web::jobs::verify_output(&good, noqui_web::MAX_SOURCE + 1).is_err());
    let mut leaking = good.clone();
    leaking["streams"][0]["tags"]["TITLE"] = "secret".into();
    assert!(noqui_web::jobs::verify_output(&leaking, 1000).is_err());
}

#[test]
fn encoded_cache_is_capped_at_115_seconds_while_private_source_may_be_300_seconds() {
    assert_eq!(
        noqui_web::jobs::source_duration(&json!({"duration":300})).unwrap(),
        300.0
    );
    let mut audio = json!({"format":{"duration":"115.0","format_name":"ogg"},"streams":[{"codec_name":"opus","channels":2,"sample_rate":"48000"}]});
    for duration in ["1.0", "37.25", "115.0", "115.05"] {
        audio["format"]["duration"] = duration.into();
        assert!(
            noqui_web::jobs::verify_output(&audio, 1000).is_ok(),
            "valid short/padded clip: {duration}"
        );
    }
    for duration in ["115.051", "116.0", "300.0"] {
        audio["format"]["duration"] = duration.into();
        assert_eq!(
            noqui_web::jobs::verify_output(&audio, 1000).err(),
            Some("VERIFY_FAILED"),
            "too-long cached output: {duration}"
        );
    }
}

#[tokio::test]
async fn old_round_readiness_and_competing_tab_do_not_become_current_playback() {
    let f = Fixture::new();
    let m = f.seed();
    let mut s = f.snapshot(1, "PREPARING");
    f.round(&mut s);
    f.sync_direct(s, Some(m));
    let (cookie, csrf) = f.login();
    let post = |client: &str, take: bool, round: &str| {
        Request::builder().method("POST").uri("/api/v1/playback").header("cookie",&cookie).header("origin",noqui_web::ORIGIN).header("content-type","application/json").header("x-noqui-csrf",&csrf).body(Body::from(json!({"clientId":client,"takeOver":take,"roundId":round,"roundRevision":"1","playbackGeneration":GENERATION,"status":"PREPARED","positionMs":30000,"bufferedUntilMs":40000,"lastEventId":null}).to_string())).unwrap()
    };
    let a = uuid::Uuid::new_v4().to_string();
    let b = uuid::Uuid::new_v4().to_string();
    assert_eq!(
        call(api::public(f.app.clone()), post(&a, false, INSTANCE))
            .await
            .0,
        StatusCode::OK
    );
    let key = auth::digest(cookie.split('=').nth(1).unwrap().as_bytes());
    assert_eq!(
        f.app.inner.lock().unwrap().sessions[&key]
            .browser
            .as_ref()
            .unwrap()
            .status,
        "CONNECTED"
    );
    assert_eq!(
        call(api::public(f.app.clone()), post(&b, false, ROUND))
            .await
            .0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        call(api::public(f.app.clone()), post(&b, true, ROUND))
            .await
            .0,
        StatusCode::OK
    );
}
