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
            playback_rate: 1,
        });
    }
    fn sync_direct(&self, s: Snapshot, media: Option<Media>) -> Value {
        let bytes = serde_json::to_vec(&s).unwrap();
        let map = media
            .map(|m| HashMap::from([(m.id.clone(), m)]))
            .unwrap_or_default();
        self.app
            .inner
            .lock()
            .unwrap()
            .sync(s, auth::digest(&bytes), map, self.app.now())
            .unwrap()
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
        song: None,
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
async fn output_check_is_a_public_silent_wav_with_the_existing_security_policy() {
    let f = Fixture::new();
    let (status, headers, body) =
        call(api::public(f.app.clone()), get("/playback-check.wav", "")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(headers["content-type"], "audio/wav");
    assert_eq!(
        headers["permissions-policy"],
        "camera=(), microphone=(), geolocation=()"
    );
    assert!(
        headers["content-security-policy"]
            .to_str()
            .unwrap()
            .contains("media-src 'self'")
    );
    assert_eq!(&body[..4], b"RIFF");
    assert_eq!(&body[8..12], b"WAVE");
    assert_eq!(&body[36..40], b"data");
    assert!(body[44..].iter().all(|sample| *sample == 0));
}

#[tokio::test]
async fn join_and_home_share_the_player_document_without_an_auth_redirect() {
    let f = Fixture::new();
    let router = api::public(f.app.clone());
    let (status, headers, home) = call(router.clone(), get("/", "")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(headers["cache-control"], "no-store");
    let (status, headers, join) = call(router, get("/join", "")).await;
    assert_eq!(status, StatusCode::OK);
    assert!(!headers.contains_key("location"));
    assert_eq!(home, join);
    let html = String::from_utf8(join).unwrap();
    assert!(html.contains("id=\"audio\""));
    assert!(html.contains("src=\"/join.js\""));
    assert!(!html.contains("src=\"/app.js\""));
}

#[tokio::test]
async fn retained_media_pins_are_private_durable_and_replaced_only_by_accepted_pin_aware_sync() {
    let f = Fixture::new();
    let media = f.seed();
    let mut snapshot = f.snapshot(1, "IDLE");
    snapshot.retained_media_ids = Some(vec![media.id.clone()]);
    let bytes = serde_json::to_vec(&snapshot).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &bytes)
        )
        .await
        .0,
        StatusCode::OK
    );
    let db = rusqlite::Connection::open(f.app.config.data.join("media.sqlite")).unwrap();
    let pins = || {
        db.query_row(
            "SELECT count(*) FROM cache_pins WHERE kind='queue' AND media_id=?",
            [MEDIA],
            |r| r.get::<_, i64>(0),
        )
        .unwrap()
    };
    assert_eq!(pins(), 1);
    let (cookie, _) = f.login();
    let public = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    assert_eq!(public.0, StatusCode::OK);
    let body = String::from_utf8(public.2).unwrap();
    assert!(!body.contains("retainedMediaIds"));
    assert!(
        !body.contains(MEDIA),
        "queued-only media is not exposed to browsers"
    );
    let legacy = serde_json::to_vec(&f.snapshot(2, "IDLE")).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &legacy)
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(pins(), 1);
    snapshot.retained_media_ids = Some(vec![]);
    let stale = serde_json::to_vec(&snapshot).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &stale)
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    assert_eq!(pins(), 1);
    let mut replacement = f.snapshot(3, "IDLE");
    replacement.instance_id = uuid::Uuid::new_v4().to_string();
    replacement.retained_media_ids = Some(vec![]);
    let bytes = serde_json::to_vec(&replacement).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &bytes)
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    assert_eq!(pins(), 1);
    let _reopened = Jobs::open(&f.app.config.data, START + 1000).unwrap();
    assert_eq!(pins(), 1);
    let mut release = f.snapshot(3, "PREPARING");
    f.round(&mut release);
    release.retained_media_ids = Some(vec![]);
    let bytes = serde_json::to_vec(&release).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &bytes)
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(pins(), 0);
    assert_eq!(
        db.query_row(
            "SELECT count(*) FROM cache_pins WHERE kind='current' AND media_id=?",
            [MEDIA],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        1
    );
    let used: i64 = db
        .query_row("SELECT last_used FROM media WHERE id=?", [MEDIA], |r| {
            r.get(0)
        })
        .unwrap();
    release.seq = "4".into();
    release.sent_at_ms = START + 1000;
    f.clock.0.store(START + 1000, Ordering::SeqCst);
    let bytes = serde_json::to_vec(&release).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &bytes)
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        db.query_row("SELECT last_used FROM media WHERE id=?", [MEDIA], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        used,
        "pin/control heartbeats are not uses"
    );
}

#[tokio::test]
async fn submission_metadata_is_saved_before_conversion_for_queued_and_cached_songs_without_public_leaks()
 {
    let f = Fixture::new();
    f.seed();
    let mut idle = f.snapshot(1, "IDLE");
    idle.rooms[0].round_number = Some(0);
    idle.rooms[0].total_rounds = Some(0);
    idle.rooms[0].registered_songs = Some(0);
    f.sync_direct(idle, None);
    let (cookie, _) = f.login();
    let db = rusqlite::Connection::open(f.app.config.data.join("media.sqlite")).unwrap();
    for (video, status) in [
        ("AAAAAAAAAAA", StatusCode::ACCEPTED),
        ("BaW_jenozKc", StatusCode::OK),
    ] {
        let id = uuid::Uuid::new_v4().to_string();
        let request = json!({"schemaVersion":1,"requestId":id,"videoId":video,"song":{"title":"PRIVATE SONG TITLE","tags":["PRIVATE TAG"]}});
        let bytes = serde_json::to_vec(&request).unwrap();
        let response = call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/media/jobs", &bytes),
        )
        .await;
        assert_eq!(response.0, status);
        for secret in ["PRIVATE SONG TITLE", "PRIVATE TAG", "youtube.com"] {
            assert!(!String::from_utf8_lossy(&response.2).contains(secret));
        }
        let row: (String, String, String, i64, i64) = db
            .query_row(
                "SELECT youtube_url,title,tags_json,created,updated FROM song_catalogue WHERE id=?",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
            )
            .unwrap();
        assert_eq!(row.0, format!("https://www.youtube.com/watch?v={video}"));
        assert_eq!(row.1, "PRIVATE SONG TITLE");
        assert_eq!(
            serde_json::from_str::<Value>(&row.2).unwrap(),
            json!(["PRIVATE TAG"])
        );
        f.clock.0.store(START + 1000, Ordering::SeqCst);
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/media/jobs", &bytes)
            )
            .await
            .0,
            status
        );
        let timestamps: (i64, i64) = db
            .query_row(
                "SELECT created,updated FROM song_catalogue WHERE id=?",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(timestamps, (row.3, row.4));
        let mut conflict = request;
        conflict["song"]["title"] = json!("DIFFERENT TITLE");
        let bytes = serde_json::to_vec(&conflict).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/media/jobs", &bytes)
            )
            .await
            .0,
            StatusCode::CONFLICT
        );
    }
    let (status, _, public) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    assert_eq!(status, StatusCode::OK);
    for secret in [
        "PRIVATE SONG TITLE",
        "PRIVATE TAG",
        "youtube.com",
        "AAAAAAAAAAA",
        "BaW_jenozKc",
    ] {
        assert!(!String::from_utf8_lossy(&public).contains(secret));
    }
    let value: Value = serde_json::from_slice(&public).unwrap();
    assert_eq!(
        value["room"]["registeredSongs"], 0,
        "catalogue size cannot become the session display denominator"
    );
    assert_eq!(
        db.query_row("SELECT count(*) FROM song_catalogue", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        2
    );
}

#[tokio::test]
async fn catalogue_import_is_private_bounded_idempotent_and_transactional() {
    let f = Fixture::new();
    let path = "/internal/v1/catalogue/import";
    let record = |id: String, title: &str| json!({"id":id,"youtubeUrl":"https://www.youtube.com/watch?v=BaW_jenozKc","title":title,"tags":["음악"]});
    let first = record(uuid::Uuid::new_v4().to_string(), "Imported First");
    let second = record(uuid::Uuid::new_v4().to_string(), "Imported Second");
    let payload = json!({"schemaVersion":1,"songs":[first.clone(),second]});
    let bytes = serde_json::to_vec(&payload).unwrap();
    let unsigned = Request::builder()
        .method("POST")
        .uri(path)
        .header("content-type", "application/json")
        .body(Body::from(bytes.clone()))
        .unwrap();
    assert!(
        call(api::internal(f.app.clone()), unsigned)
            .await
            .0
            .is_client_error()
    );
    let public = Request::builder()
        .method("POST")
        .uri(path)
        .header("content-type", "application/json")
        .body(Body::from(bytes.clone()))
        .unwrap();
    assert_eq!(
        call(api::public(f.app.clone()), public).await.0,
        StatusCode::NOT_FOUND
    );
    for _ in 0..2 {
        let response = call(api::internal(f.app.clone()), f.signed("POST", path, &bytes)).await;
        assert_eq!(response.0, StatusCode::OK);
        assert_eq!(
            serde_json::from_slice::<Value>(&response.2).unwrap(),
            json!({"savedCount":2})
        );
    }
    let db = rusqlite::Connection::open(f.app.config.data.join("media.sqlite")).unwrap();
    let count = || {
        db.query_row("SELECT count(*) FROM song_catalogue", [], |r| {
            r.get::<_, i64>(0)
        })
        .unwrap()
    };
    assert_eq!(count(), 2);
    let mut conflict = first.clone();
    conflict["title"] = json!("Changed Existing");
    let rollback = json!({"schemaVersion":1,"songs":[record(uuid::Uuid::new_v4().to_string(),"Must Roll Back"),conflict]});
    let bytes = serde_json::to_vec(&rollback).unwrap();
    assert_eq!(
        call(api::internal(f.app.clone()), f.signed("POST", path, &bytes))
            .await
            .0,
        StatusCode::CONFLICT
    );
    assert_eq!(count(), 2);
    let fifty: Vec<Value> = (0..50)
        .map(|i| record(uuid::Uuid::new_v4().to_string(), &format!("Song {i}")))
        .collect();
    let bytes = serde_json::to_vec(&json!({"schemaVersion":1,"songs":fifty})).unwrap();
    assert!(bytes.len() < 65536);
    let response = call(api::internal(f.app.clone()), f.signed("POST", path, &bytes)).await;
    assert_eq!(response.0, StatusCode::OK);
    assert_eq!(
        serde_json::from_slice::<Value>(&response.2).unwrap()["savedCount"],
        50
    );
    assert_eq!(count(), 52);
    let too_many: Vec<Value> = (0..51).map(|_| first.clone()).collect();
    for (payload, status) in [
        (
            json!({"schemaVersion":1,"songs":too_many}),
            StatusCode::BAD_REQUEST,
        ),
        (
            json!({"schemaVersion":2,"songs":[]}),
            StatusCode::BAD_REQUEST,
        ),
        (
            json!({"schemaVersion":1,"songs":[],"padding":"x".repeat(65536)}),
            StatusCode::PAYLOAD_TOO_LARGE,
        ),
    ] {
        let bytes = serde_json::to_vec(&payload).unwrap();
        assert_eq!(
            call(api::internal(f.app.clone()), f.signed("POST", path, &bytes))
                .await
                .0,
            status
        );
        assert_eq!(count(), 52);
    }
    for table in ["jobs", "media", "requests"] {
        assert_eq!(
            db.query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
    }
}

#[tokio::test]
async fn rejected_or_failed_job_registration_cannot_leave_partial_catalogue_records() {
    let f = Fixture::new();
    let db = rusqlite::Connection::open(f.app.config.data.join("media.sqlite")).unwrap();
    for i in 0..64 {
        f.app
            .jobs
            .enqueue(job(&format!("AAAAAAAA{i:03}")), START)
            .await
            .unwrap();
    }
    let mut rejected = job("ZZZZZZZZZZZ");
    rejected.song = Some(noqui_web::catalogue::SongMetadata {
        title: "Queue Full Song".into(),
        tags: vec![],
    });
    assert_eq!(
        f.app.jobs.enqueue(rejected, START).await.unwrap_err().code,
        "QUEUE_FULL"
    );
    assert_eq!(
        db.query_row("SELECT count(*) FROM song_catalogue", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0
    );
    let g = Fixture::new();
    let db = rusqlite::Connection::open(g.app.config.data.join("media.sqlite")).unwrap();
    db.execute_batch("CREATE TRIGGER reject_receipt BEFORE INSERT ON requests BEGIN SELECT RAISE(ABORT,'injected failure'); END;").unwrap();
    let mut rejected = job("ZZZZZZZZZZZ");
    rejected.song = Some(noqui_web::catalogue::SongMetadata {
        title: "Storage Failure Song".into(),
        tags: vec![],
    });
    assert_eq!(
        g.app.jobs.enqueue(rejected, START).await.unwrap_err().code,
        "STORAGE_UNAVAILABLE"
    );
    for table in ["song_catalogue", "jobs", "requests"] {
        assert_eq!(
            db.query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
    }
}

#[tokio::test]
async fn retained_media_rejects_invalid_duplicate_unready_and_oversized_lists_without_releasing_pins()
 {
    let f = Fixture::new();
    let media = f.seed();
    let mut snapshot = f.snapshot(1, "IDLE");
    snapshot.retained_media_ids = Some(vec![media.id.clone()]);
    let bytes = serde_json::to_vec(&snapshot).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &bytes)
        )
        .await
        .0,
        StatusCode::OK
    );
    let mut next = serde_json::to_value(f.snapshot(2, "IDLE")).unwrap();
    let too_many: Vec<String> = (0..65).map(|_| uuid::Uuid::new_v4().to_string()).collect();
    for (ids, status) in [
        (Value::Null, StatusCode::BAD_REQUEST),
        (json!("invalid"), StatusCode::BAD_REQUEST),
        (json!([MEDIA, MEDIA]), StatusCode::BAD_REQUEST),
        (json!(["invalid"]), StatusCode::BAD_REQUEST),
        (json!(too_many), StatusCode::BAD_REQUEST),
        (
            json!([uuid::Uuid::new_v4().to_string()]),
            StatusCode::CONFLICT,
        ),
    ] {
        next["retainedMediaIds"] = ids;
        let bytes = serde_json::to_vec(&next).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/sync", &bytes)
            )
            .await
            .0,
            status
        );
        assert_eq!(
            f.app.inner.lock().unwrap().snapshot.as_ref().unwrap().seq,
            "1"
        );
    }
    let db = rusqlite::Connection::open(f.app.config.data.join("media.sqlite")).unwrap();
    assert_eq!(
        db.query_row(
            "SELECT count(*) FROM cache_pins WHERE kind='queue'",
            [],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        1
    );
}

#[tokio::test(start_paused = true)]
async fn idle_sse_heartbeats_keep_presence_without_audio_or_browser_timers_then_expire() {
    let f = Fixture::new();
    assert_eq!(
        f.sync_direct(f.snapshot(1, "IDLE"), None)["browsers"][0]["connected"],
        false
    );
    let (cookie, _) = f.login();
    call(api::public(f.app.clone()), get("/api/v1/me", &cookie)).await;
    assert_eq!(
        f.sync_direct(f.snapshot(2, "IDLE"), None)["browsers"][0]["connected"],
        false
    );
    let response = api::public(f.app.clone())
        .oneshot(get("/api/v1/events", &cookie))
        .await
        .unwrap();
    let mut stream = response.into_body();
    assert!(
        String::from_utf8(
            stream
                .frame()
                .await
                .unwrap()
                .unwrap()
                .into_data()
                .unwrap()
                .to_vec()
        )
        .unwrap()
        .contains("event: state")
    );
    for tick in 1..=12 {
        let mut next = Box::pin(stream.frame());
        assert!(futures_util::poll!(next.as_mut()).is_pending());
        f.clock.0.store(START + tick * 5000, Ordering::SeqCst);
        f.sync_direct(f.snapshot((tick + 2) as u64, "IDLE"), None);
        tokio::time::advance(std::time::Duration::from_secs(5)).await;
        let event =
            String::from_utf8(next.await.unwrap().unwrap().into_data().unwrap().to_vec()).unwrap();
        assert!(
            event.contains("event: lease"),
            "actual SSE heartbeat, with no state notification"
        );
        let feedback = f.sync_direct(f.snapshot((tick + 2) as u64, "IDLE"), None);
        assert_eq!(feedback["browsers"][0]["uuid"], PLAYER);
        assert_eq!(feedback["browsers"][0]["membershipId"], MEMBER);
        assert_eq!(feedback["browsers"][0]["connected"], true);
        assert_eq!(
            feedback["browsers"][0]["online"], false,
            "no active audio-client lease"
        );
        assert!(feedback["browsers"][0]["clientId"].is_null());
    }
    drop(stream);
    f.clock.0.store(START + 70000, Ordering::SeqCst);
    assert_eq!(
        f.sync_direct(f.snapshot(15, "IDLE"), None)["browsers"][0]["connected"],
        true
    );
    f.clock.0.store(START + 80000, Ordering::SeqCst);
    call(api::public(f.app.clone()), get("/api/v1/me", &cookie)).await;
    assert_eq!(
        f.sync_direct(f.snapshot(16, "IDLE"), None)["browsers"][0]["connected"],
        false
    );
}

#[tokio::test]
async fn signed_presence_is_independent_of_readiness_and_ignores_failed_writes() {
    let f = Fixture::new();
    f.sync_direct(f.snapshot(1, "IDLE"), None);
    let (cookie, csrf) = f.login();
    let client = uuid::Uuid::new_v4().to_string();
    let report = |csrf: &str| {
        Request::builder().method("POST").uri("/api/v1/playback")
        .header("cookie", &cookie).header("origin", noqui_web::ORIGIN)
        .header("content-type", "application/json").header("x-noqui-csrf", csrf)
        .body(Body::from(json!({"clientId":client,"takeOver":false,"roundId":null,"roundRevision":null,"playbackGeneration":null,"status":"FAILED","positionMs":null,"bufferedUntilMs":null,"lastEventId":null}).to_string())).unwrap()
    };
    assert_eq!(
        call(api::public(f.app.clone()), report("stale-csrf"))
            .await
            .0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        f.sync_direct(f.snapshot(2, "IDLE"), None)["browsers"][0]["connected"],
        false
    );
    assert_eq!(
        call(api::public(f.app.clone()), report(&csrf)).await.0,
        StatusCode::OK
    );
    let bytes = serde_json::to_vec(&f.snapshot(3, "IDLE")).unwrap();
    let (status, _, body) = call(
        api::internal(f.app.clone()),
        f.signed("POST", "/internal/v1/sync", &bytes),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let feedback: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(feedback["browsers"][0]["connected"], true);
    assert_eq!(feedback["browsers"][0]["status"], "FAILED");
    f.clock.0.store(START + 10000, Ordering::SeqCst);
    f.sync_direct(f.snapshot(4, "IDLE"), None);
    f.clock.0.store(START + 20000, Ordering::SeqCst);
    let feedback = f.sync_direct(f.snapshot(5, "IDLE"), None);
    assert_eq!(feedback["browsers"][0]["connected"], false);
    assert_eq!(
        feedback["browsers"][0]["online"], true,
        "audio lease is a distinct, longer-lived signal"
    );
    assert_eq!(
        call(api::public(f.app.clone()), report("stale-csrf"))
            .await
            .0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        f.sync_direct(f.snapshot(6, "IDLE"), None)["browsers"][0]["connected"],
        false
    );
}

#[tokio::test]
async fn an_unconsumed_sse_response_does_not_keep_an_orphaned_connection_present() {
    let f = Fixture::new();
    f.sync_direct(f.snapshot(1, "IDLE"), None);
    let (cookie, _) = f.login();
    let response = api::public(f.app.clone())
        .oneshot(get("/api/v1/events", &cookie))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    f.clock.0.store(START + 10000, Ordering::SeqCst);
    assert_eq!(
        f.sync_direct(f.snapshot(2, "IDLE"), None)["browsers"][0]["connected"],
        true
    );
    f.clock.0.store(START + 20000, Ordering::SeqCst);
    assert_eq!(
        f.sync_direct(f.snapshot(3, "IDLE"), None)["browsers"][0]["connected"],
        false
    );
    drop(response);
}

#[tokio::test]
async fn presence_requires_fresh_authority_and_current_membership() {
    for reason in ["membership", "instance", "leave", "logout"] {
        let f = Fixture::new();
        f.sync_direct(f.snapshot(1, "IDLE"), None);
        let (cookie, csrf) = f.login();
        assert_eq!(
            call(api::public(f.app.clone()), get("/api/v1/state", &cookie))
                .await
                .0,
            StatusCode::OK
        );
        let snapshot = f.snapshot(2, "IDLE");
        f.sync_direct(snapshot.clone(), None);
        f.clock.0.store(START + 15000, Ordering::SeqCst);
        assert_eq!(
            f.sync_direct(snapshot, None)["browsers"][0]["connected"],
            false,
            "duplicate heartbeat cannot revive authority"
        );
        if reason == "logout" {
            let request = Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header("cookie", &cookie)
                .header("origin", noqui_web::ORIGIN)
                .header("content-type", "application/json")
                .header("x-noqui-csrf", &csrf)
                .body(Body::from("{}"))
                .unwrap();
            assert_eq!(
                call(api::public(f.app.clone()), request).await.0,
                StatusCode::NO_CONTENT
            );
        }
        let mut next = f.snapshot(3, "IDLE");
        match reason {
            "membership" => next.roster[0].membership_id = uuid::Uuid::new_v4().to_string(),
            "instance" => next.instance_id = uuid::Uuid::new_v4().to_string(),
            "leave" => next.roster.clear(),
            _ => {}
        }
        let feedback = f.sync_direct(next, None);
        assert!(
            feedback["browsers"]
                .as_array()
                .unwrap()
                .iter()
                .all(|browser| browser["connected"] == false)
        );
        assert_eq!(
            call(api::public(f.app.clone()), get("/api/v1/state", &cookie))
                .await
                .0,
            StatusCode::UNAUTHORIZED
        );
    }
}

#[tokio::test]
async fn replacing_browser_cookie_retires_old_identity_and_stale_logout_cannot_touch_new_login() {
    let f = Fixture::new();
    let second = "10000000-0000-4000-8000-000000000002";
    let member = "20000000-0000-4000-8000-000000000002";
    let mut snapshot = f.snapshot(1, "IDLE");
    snapshot.roster.push(Player {
        uuid: second.into(),
        membership_id: member.into(),
        nickname: "PlayerTwo".into(),
        texture_hash: None,
        room_id: ROOM.into(),
    });
    f.sync_direct(snapshot, None);
    let (old_cookie, old_csrf) = f.login();
    let token = auth::random();
    f.app
        .inner
        .lock()
        .unwrap()
        .register(
            TokenRequest {
                schema_version: 1,
                request_id: uuid::Uuid::new_v4().to_string(),
                instance_id: INSTANCE.into(),
                uuid: second.into(),
                membership_id: member.into(),
                room_id: ROOM.into(),
                token_sha256: auth::digest(token.as_bytes()),
            },
            "register-two".into(),
            f.app.now(),
        )
        .unwrap();
    let redeem = Request::builder()
        .method("POST")
        .uri("/api/v1/auth/redeem")
        .header("cookie", &old_cookie)
        .header("origin", noqui_web::ORIGIN)
        .header("content-type", "application/json")
        .body(Body::from(json!({"token":token}).to_string()))
        .unwrap();
    let (status, headers, body) = call(api::public(f.app.clone()), redeem).await;
    assert_eq!(status, StatusCode::OK);
    let new_cookie = headers[header::SET_COOKIE]
        .to_str()
        .unwrap()
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let login: Value = serde_json::from_slice(&body).unwrap();
    let new_csrf = login["csrfToken"].as_str().unwrap();
    assert_eq!(login["me"]["uuid"], second);
    assert_eq!(
        call(
            api::public(f.app.clone()),
            get("/api/v1/state", &old_cookie)
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    let logout = |cookie: &str, csrf: &str| {
        Request::builder()
            .method("POST")
            .uri("/api/v1/auth/logout")
            .header("cookie", cookie)
            .header("origin", noqui_web::ORIGIN)
            .header("content-type", "application/json")
            .header("x-noqui-csrf", csrf)
            .body(Body::from("{}"))
            .unwrap()
    };
    let (status, headers, _) =
        call(api::public(f.app.clone()), logout(&new_cookie, &old_csrf)).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert!(!headers.contains_key(header::SET_COOKIE));
    assert_eq!(
        call(
            api::public(f.app.clone()),
            get("/api/v1/state", &new_cookie)
        )
        .await
        .0,
        StatusCode::OK
    );
    let invalid_redeem = Request::builder()
        .method("POST")
        .uri("/api/v1/auth/redeem")
        .header("cookie", &new_cookie)
        .header("origin", noqui_web::ORIGIN)
        .header("content-type", "application/json")
        .body(Body::from(json!({"token":auth::random()}).to_string()))
        .unwrap();
    assert_eq!(
        call(api::public(f.app.clone()), invalid_redeem).await.0,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        call(
            api::public(f.app.clone()),
            get("/api/v1/state", &new_cookie)
        )
        .await
        .0,
        StatusCode::OK,
        "invalid join must not revoke an existing login"
    );
    let (status, headers, _) =
        call(api::public(f.app.clone()), logout(&new_cookie, new_csrf)).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert!(
        !headers.contains_key(header::SET_COOKIE),
        "a delayed logout response must not clear a later login cookie"
    );
    assert_eq!(
        call(
            api::public(f.app.clone()),
            get("/api/v1/state", &new_cookie)
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn opus_padding_is_ready_metadata_while_public_round_keeps_exact_40_second_clip() {
    let f = Fixture::new();
    let mut media = f.seed();
    let probe = json!({"format":{"duration":"40.007000","format_name":"ogg"},"streams":[{"codec_name":"opus","channels":2,"sample_rate":"48000","tags":{"encoder":"Lavc"}}]});
    assert_eq!(
        noqui_web::jobs::verify_output(&probe, 704745).unwrap(),
        (40007, 704745)
    );
    let db = rusqlite::Connection::open(f.app.jobs.root.join("media.sqlite")).unwrap();
    db.execute("UPDATE media SET duration=40007 WHERE id=?", [MEDIA])
        .unwrap();
    media.duration_ms = 40007;
    let (_, job) = f.app.jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap();
    assert_eq!(job["state"], "READY");
    assert_eq!(job["durationMs"], 40007);
    let mut snapshot = f.snapshot(1, "PREPARING");
    f.round(&mut snapshot);
    snapshot.rooms[0].round.as_mut().unwrap().clip_end_ms = 40000;
    f.sync_direct(snapshot, Some(media));
    let (cookie, _) = f.login();
    let (status, _, body) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    assert_eq!(status, StatusCode::OK);
    let public: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(public["round"]["clipEndMs"], 40000);
    assert_eq!(public["media"]["durationMs"], 40007);
    assert!(public["reveal"].is_null());
    f.clock.0.store(START + 15000, Ordering::SeqCst);
    assert_eq!(
        call(api::public(f.app.clone()), get("/api/v1/state", &cookie))
            .await
            .0,
        StatusCode::SERVICE_UNAVAILABLE,
        "authority expiry still blocks access"
    );
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
async fn playback_rate_defaults_to_one_projects_two_and_rejects_other_values() {
    for rate in [None, Some(json!(1)), Some(json!(2))] {
        let f = Fixture::new();
        f.seed();
        let mut snapshot = f.snapshot(1, "PREPARING");
        f.round(&mut snapshot);
        let mut value = serde_json::to_value(snapshot).unwrap();
        match &rate {
            Some(rate) => value["rooms"][0]["round"]["playbackRate"] = rate.clone(),
            None => {
                value["rooms"][0]["round"]
                    .as_object_mut()
                    .unwrap()
                    .remove("playbackRate");
            }
        }
        let bytes = serde_json::to_vec(&value).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/sync", &bytes)
            )
            .await
            .0,
            StatusCode::OK
        );
        let (cookie, _) = f.login();
        let (status, _, body) =
            call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
        assert_eq!(status, StatusCode::OK);
        let public: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(public["round"]["playbackRate"], rate.unwrap_or(json!(1)));
        assert_eq!(public["round"]["clipEndMs"], 115000);
        assert_eq!(public["media"]["durationMs"], 115000);
    }
    for rate in [
        json!(0),
        json!(3),
        json!(-1),
        json!(1.5),
        json!(1.0),
        json!(2.0),
        json!("2"),
        json!(null),
        json!(256),
    ] {
        let f = Fixture::new();
        f.seed();
        let mut snapshot = f.snapshot(1, "PREPARING");
        f.round(&mut snapshot);
        let mut value = serde_json::to_value(snapshot).unwrap();
        value["rooms"][0]["round"]["playbackRate"] = rate;
        let bytes = serde_json::to_vec(&value).unwrap();
        let status = call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &bytes),
        )
        .await
        .0;
        assert!(status == StatusCode::BAD_REQUEST || status == StatusCode::UNPROCESSABLE_ENTITY);
        assert!(f.app.inner.lock().unwrap().snapshot.is_none());
    }
}

#[tokio::test]
async fn double_speed_keeps_source_readiness_pause_resume_bounds_and_freezes_rate_per_round() {
    let f = Fixture::new();
    let media = f.seed();
    let mut snapshot = f.snapshot(1, "PREPARING");
    f.round(&mut snapshot);
    let round = snapshot.rooms[0].round.as_mut().unwrap();
    round.playback_rate = 2;
    round.anchor_position_ms = 90000;
    f.sync_direct(snapshot, Some(media.clone()));
    let (cookie, csrf) = f.login();
    let report = Request::builder().method("POST").uri("/api/v1/playback")
        .header("cookie", &cookie).header("origin", noqui_web::ORIGIN).header("content-type", "application/json").header("x-noqui-csrf", csrf)
        .body(Body::from(json!({"clientId":uuid::Uuid::new_v4(),"takeOver":false,"roundId":ROUND,"roundRevision":"1","playbackGeneration":GENERATION,"status":"PREPARED","positionMs":90000,"bufferedUntilMs":91000,"lastEventId":null}).to_string())).unwrap();
    assert_eq!(
        call(api::public(f.app.clone()), report).await.0,
        StatusCode::OK
    );
    let mut paused = f.snapshot(2, "PAUSED");
    f.round(&mut paused);
    let round = paused.rooms[0].round.as_mut().unwrap();
    round.playback_rate = 2;
    round.playing = false;
    round.anchor_position_ms = 90000;
    let feedback = f.sync_direct(paused.clone(), Some(media.clone()));
    assert_eq!(feedback["browsers"][0]["positionMs"], 90000);
    assert_eq!(feedback["browsers"][0]["status"], "PREPARED");
    let mut resumed = f.snapshot(3, "COUNTDOWN");
    f.round(&mut resumed);
    let round = resumed.rooms[0].round.as_mut().unwrap();
    round.playback_rate = 2;
    round.anchor_position_ms = 90000;
    round.anchor_at_ms = START + 3000;
    f.sync_direct(resumed.clone(), Some(media.clone()));
    let (_, _, body) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    let public: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(public["round"]["anchorPositionMs"], 90000);
    assert_eq!(public["round"]["playbackRate"], 2);
    assert_eq!(public["round"]["anchorAtMs"], START + 3000);
    assert_eq!(public["round"]["clipEndMs"], 115000);
    let mut invalid = resumed.clone();
    invalid.rooms[0].round.as_mut().unwrap().anchor_position_ms = 115001;
    let map = HashMap::from([(media.id.clone(), media.clone())]);
    assert_eq!(
        noqui_web::state::validate(&invalid, &map, START)
            .unwrap_err()
            .code,
        "INVALID_TIMELINE"
    );
    let mut changed = resumed;
    changed.seq = "4".into();
    changed.rooms[0].room_revision = "4".into();
    let round = changed.rooms[0].round.as_mut().unwrap();
    round.revision = "4".into();
    round.playback_rate = 1;
    let bytes = serde_json::to_vec(&changed).unwrap();
    assert_eq!(
        f.app
            .inner
            .lock()
            .unwrap()
            .sync(changed.clone(), auth::digest(&bytes), map, START)
            .unwrap_err()
            .code,
        "REVISION_CONFLICT"
    );
    changed.rooms[0].round.as_mut().unwrap().round_id = uuid::Uuid::new_v4().to_string();
    f.sync_direct(changed, Some(media));
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
        "Max-Age=28800",
    ] {
        assert!(cookie.contains(attribute));
    }
    let cookie = cookie.split(';').next().unwrap();
    let me: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(me["me"]["uuid"], PLAYER);
    assert_eq!(me["sessionExpiresAtMs"], START + 28800000);
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
async fn membership_change_revokes_but_authority_expiry_only_suspends_access() {
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
        StatusCode::OK
    );
    assert_eq!(
        call(api::public(f.app.clone()), get("/api/v1/state", &cookie))
            .await
            .0,
        StatusCode::SERVICE_UNAVAILABLE
    );
    f.sync_direct(f.snapshot(4, "IDLE"), None);
    assert_eq!(
        call(api::public(f.app.clone()), get("/api/v1/state", &cookie))
            .await
            .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn active_session_outlives_join_token_and_idle_timeout_but_keeps_absolute_cap() {
    let f = Fixture::new();
    f.sync_direct(f.snapshot(1, "IDLE"), None);
    let (cookie, csrf) = f.login();
    let client = uuid::Uuid::new_v4().to_string();
    for elapsed in (10000..28800000).step_by(10000) {
        f.clock.0.store(START + elapsed, Ordering::SeqCst);
        f.sync_direct(f.snapshot((elapsed / 10000 + 1) as u64, "IDLE"), None);
        assert_eq!(
            call(api::public(f.app.clone()), get("/api/v1/state", &cookie))
                .await
                .0,
            StatusCode::OK
        );
        if elapsed % 30000 == 0 {
            let request = Request::builder().method("POST").uri("/api/v1/playback")
                .header("cookie", &cookie).header("origin", noqui_web::ORIGIN)
                .header("content-type", "application/json").header("x-noqui-csrf", &csrf)
                .body(Body::from(json!({"clientId":client,"takeOver":false,"roundId":null,"roundRevision":null,"playbackGeneration":null,"status":"CONNECTED","positionMs":null,"bufferedUntilMs":null,"lastEventId":null}).to_string())).unwrap();
            let (status, _, body) = call(api::public(f.app.clone()), request).await;
            assert_eq!(status, StatusCode::OK);
            let value: Value = serde_json::from_slice(&body).unwrap();
            assert_eq!(value["leaseExpiresAtMs"], START + elapsed + 60000);
        }
    }
    let (status, _, body) = call(api::public(f.app.clone()), get("/api/v1/me", &cookie)).await;
    assert_eq!(status, StatusCode::OK);
    let me: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(
        me["sessionExpiresAtMs"],
        START + 28800000,
        "active requests do not extend the absolute cap"
    );
    f.clock.0.store(START + 28800000, Ordering::SeqCst);
    f.sync_direct(f.snapshot(2881, "IDLE"), None);
    assert_eq!(
        call(api::public(f.app.clone()), get("/api/v1/me", &cookie))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn sse_authority_gap_blocks_room_media_and_control_then_recovers_same_cookie() {
    async fn next_event(body: &mut Body) -> String {
        let frame = tokio::time::timeout(std::time::Duration::from_secs(1), body.frame())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        String::from_utf8(frame.into_data().unwrap().to_vec()).unwrap()
    }
    let f = Fixture::new();
    let media = f.seed();
    let mut snapshot = f.snapshot(1, "PREPARING");
    f.round(&mut snapshot);
    f.sync_direct(snapshot, Some(media.clone()));
    let (cookie, csrf) = f.login();
    let response = api::public(f.app.clone())
        .oneshot(get("/api/v1/events", &cookie))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let mut stream = response.into_body();
    assert!(next_event(&mut stream).await.contains("event: state"));
    f.clock.0.store(START + 16000, Ordering::SeqCst);
    f.app.notify();
    let offline = next_event(&mut stream).await;
    assert!(offline.contains("event: offline") && offline.contains("PLUGIN_OFFLINE"));
    assert!(!offline.contains("revoked") && !offline.contains("roundId"));
    for path in [
        "/api/v1/state".to_owned(),
        format!("/media/{}.ogg", media.sha256),
        "/api/v1/events".to_owned(),
    ] {
        let (status, _, body) = call(api::public(f.app.clone()), get(&path, &cookie)).await;
        assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
        let value: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(value["error"]["code"], "PLUGIN_OFFLINE");
    }
    let request = Request::builder().method("POST").uri("/api/v1/playback")
        .header("cookie", &cookie).header("origin", noqui_web::ORIGIN)
        .header("content-type", "application/json").header("x-noqui-csrf", &csrf)
        .body(Body::from(json!({"clientId":uuid::Uuid::new_v4(),"takeOver":false,"roundId":null,"roundRevision":null,"playbackGeneration":null,"status":"CONNECTED","positionMs":null,"bufferedUntilMs":null,"lastEventId":null}).to_string())).unwrap();
    assert_eq!(
        call(api::public(f.app.clone()), request).await.0,
        StatusCode::SERVICE_UNAVAILABLE
    );
    let mut snapshot = f.snapshot(2, "PREPARING");
    f.round(&mut snapshot);
    f.sync_direct(snapshot, Some(media.clone()));
    f.app.notify();
    assert!(next_event(&mut stream).await.contains("event: state"));
    assert_eq!(
        call(api::public(f.app.clone()), get("/api/v1/state", &cookie))
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        call(
            api::public(f.app.clone()),
            get(&format!("/media/{}.ogg", media.sha256), &cookie)
        )
        .await
        .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn dormant_sessions_cannot_survive_changed_instance_membership_leave_logout_or_idle_expiry() {
    for reason in ["instance", "membership", "leave", "logout", "idle"] {
        let f = Fixture::new();
        f.sync_direct(f.snapshot(1, "IDLE"), None);
        let (cookie, csrf) = f.login();
        f.clock.0.store(
            START + if reason == "idle" { 1800000 } else { 16000 },
            Ordering::SeqCst,
        );
        if reason == "logout" {
            let request = Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header("cookie", &cookie)
                .header("origin", noqui_web::ORIGIN)
                .header("content-type", "application/json")
                .header("x-noqui-csrf", csrf)
                .body(Body::from("{}"))
                .unwrap();
            assert_eq!(
                call(api::public(f.app.clone()), request).await.0,
                StatusCode::NO_CONTENT
            );
        }
        let mut snapshot = f.snapshot(2, "IDLE");
        match reason {
            "instance" => snapshot.instance_id = uuid::Uuid::new_v4().to_string(),
            "membership" => snapshot.roster[0].membership_id = uuid::Uuid::new_v4().to_string(),
            "leave" => snapshot.roster.clear(),
            _ => {}
        }
        let instance = snapshot.instance_id.clone();
        f.sync_direct(snapshot, None);
        assert_eq!(
            call(api::public(f.app.clone()), get("/api/v1/state", &cookie))
                .await
                .0,
            StatusCode::UNAUTHORIZED,
            "{reason}"
        );
        let mut restored = f.snapshot(3, "IDLE");
        restored.instance_id = instance;
        f.sync_direct(restored, None);
        assert_eq!(
            call(api::public(f.app.clone()), get("/api/v1/state", &cookie))
                .await
                .0,
            StatusCode::UNAUTHORIZED,
            "restoring roster must not revive {reason}"
        );
    }
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

#[test]
fn token_redemption_during_authority_gap_waits_for_fresh_matching_authority() {
    for changed in [false, true] {
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
            .register(request, "join".into(), START)
            .unwrap();
        f.clock.0.store(START + 16000, Ordering::SeqCst);
        assert_eq!(
            f.app
                .inner
                .lock()
                .unwrap()
                .redeem(&token, f.app.now())
                .err()
                .unwrap()
                .code,
            "PLUGIN_OFFLINE"
        );
        let mut snapshot = f.snapshot(2, "IDLE");
        if changed {
            snapshot.roster[0].membership_id = uuid::Uuid::new_v4().to_string();
        }
        f.sync_direct(snapshot, None);
        let result = f.app.inner.lock().unwrap().redeem(&token, f.app.now());
        if changed {
            assert_eq!(result.err().unwrap().code, "JOIN_INVALID");
        } else {
            assert!(result.is_ok());
        }
    }
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
async fn optional_round_progress_projects_across_phases_and_preserves_legacy_snapshots() {
    let f = Fixture::new();
    f.seed();
    let legacy = serde_json::to_vec(&f.snapshot(1, "IDLE")).unwrap();
    assert!(!String::from_utf8_lossy(&legacy).contains("roundNumber"));
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &legacy)
        )
        .await
        .0,
        StatusCode::OK
    );
    let (cookie, _) = f.login();
    let (_, _, bytes) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    let value: Value = serde_json::from_slice(&bytes).unwrap();
    assert!(value["room"].get("roundNumber").is_none());
    assert!(value["room"].get("totalRounds").is_none());

    for (index, (phase, number, total)) in [
        ("IDLE", 0, 1),
        ("WAITING_FOR_MEDIA", 1, 100),
        ("PREPARING", 1, 100),
        ("COUNTDOWN", 1, 100),
        ("PLAYING", 1, 100),
        ("PAUSED", 1, 100),
        ("REVEAL", 1, 100),
        ("IDLE", 1, 100),
        ("ENDED", 1000, 1000),
    ]
    .into_iter()
    .enumerate()
    {
        let mut snapshot = f.snapshot(index as u64 + 2, phase);
        snapshot.rooms[0].round_number = Some(number);
        snapshot.rooms[0].total_rounds = Some(total);
        if ["PREPARING", "COUNTDOWN", "PLAYING", "PAUSED", "REVEAL"].contains(&phase) {
            f.round(&mut snapshot);
            snapshot.rooms[0].round.as_mut().unwrap().playing =
                matches!(phase, "COUNTDOWN" | "PLAYING");
        }
        if phase == "REVEAL" {
            snapshot.rooms[0].reveal = Some(Reveal {
                title: "정답 제목".into(),
                aliases: vec!["별칭".into()],
                tags: vec!["태그".into()],
                youtube_url: "https://www.youtube.com/watch?v=BaW_jenozKc".into(),
            });
        }
        let body = serde_json::to_vec(&snapshot).unwrap();
        let (status, _, _) = call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &body),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "phase {phase}");
        let (status, _, bytes) =
            call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
        assert_eq!(status, StatusCode::OK);
        let value: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["room"]["roundNumber"], number);
        assert_eq!(value["room"]["totalRounds"], total);
        if phase != "REVEAL" {
            for secret in [
                "BaW_jenozKc",
                "youtube",
                "title",
                "aliases",
                "tags",
                "정답 제목",
                "별칭",
                "태그",
            ] {
                assert!(
                    !String::from_utf8_lossy(&bytes).contains(secret),
                    "leak in {phase}: {secret}"
                );
            }
        } else {
            assert_eq!(value["reveal"]["title"], "정답 제목");
        }
        if matches!(phase, "IDLE" | "WAITING_FOR_MEDIA" | "ENDED") {
            assert!(value["round"].is_null());
        }
    }
    f.sync_direct(f.snapshot(11, "IDLE"), None);
    let (_, _, bytes) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    let value: Value = serde_json::from_slice(&bytes).unwrap();
    assert!(value["room"].get("roundNumber").is_none());
    assert!(value["room"].get("totalRounds").is_none());
}

#[tokio::test]
async fn registered_songs_projects_session_count_in_all_phases_independently_of_the_limit() {
    let f = Fixture::new();
    f.seed();
    f.sync_direct(f.snapshot(1, "IDLE"), None);
    let (cookie, _) = f.login();
    for (index, (phase, number, registered, total)) in [
        ("IDLE", 0, 0, 200),
        ("WAITING_FOR_MEDIA", 1, 3, 2),
        ("PREPARING", 1, 3, 200),
        ("COUNTDOWN", 1, 3, 0),
        ("PLAYING", 1, 3, 200),
        ("PAUSED", 1, 2, 0),
        ("REVEAL", 1, 2, 200),
        ("IDLE", 1, 2, 0),
        ("ENDED", 1, 1, 200),
        ("ENDED", u32::MAX, u32::MAX, 0),
    ]
    .into_iter()
    .enumerate()
    {
        let mut snapshot = f.snapshot(index as u64 + 2, phase);
        let room = &mut snapshot.rooms[0];
        room.round_number = Some(number);
        room.total_rounds = Some(total);
        room.registered_songs = Some(registered);
        if ["PREPARING", "COUNTDOWN", "PLAYING", "PAUSED", "REVEAL"].contains(&phase) {
            f.round(&mut snapshot);
            snapshot.rooms[0].round.as_mut().unwrap().playing =
                matches!(phase, "COUNTDOWN" | "PLAYING");
        }
        let body = serde_json::to_vec(&snapshot).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/sync", &body)
            )
            .await
            .0,
            StatusCode::OK,
            "phase {phase}"
        );
        let (status, _, bytes) =
            call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
        assert_eq!(status, StatusCode::OK);
        let value: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["room"]["registeredSongs"], registered);
        assert_eq!(value["room"]["roundNumber"], number);
        assert_eq!(value["room"]["totalRounds"], total);
    }
    let legacy = f.snapshot(12, "IDLE");
    let bytes = serde_json::to_vec(&legacy).unwrap();
    assert!(!String::from_utf8_lossy(&bytes).contains("registeredSongs"));
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &bytes)
        )
        .await
        .0,
        StatusCode::OK
    );
    let (_, _, bytes) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    assert!(
        serde_json::from_slice::<Value>(&bytes).unwrap()["room"]
            .get("registeredSongs")
            .is_none()
    );
}

#[tokio::test]
async fn registered_songs_rejects_invalid_numeric_values_without_accepting_state() {
    let f = Fixture::new();
    for registered in [
        json!(-1),
        json!(1.5),
        json!(1.0),
        json!(true),
        json!("3"),
        json!(4294967296_u64),
    ] {
        let mut snapshot = serde_json::to_value(f.snapshot(1, "IDLE")).unwrap();
        snapshot["rooms"][0]["registeredSongs"] = registered;
        let body = serde_json::to_vec(&snapshot).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/sync", &body)
            )
            .await
            .0,
            StatusCode::BAD_REQUEST
        );
        assert!(f.app.inner.lock().unwrap().snapshot.is_none());
    }
    let mut snapshot = serde_json::to_value(f.snapshot(1, "IDLE")).unwrap();
    snapshot["rooms"][0]["registeredSongs"] = Value::Null;
    let snapshot: Snapshot = serde_json::from_value(snapshot).unwrap();
    assert!(snapshot.rooms[0].registered_songs.is_none());
    assert!(
        !serde_json::to_string(&snapshot)
            .unwrap()
            .contains("registeredSongs")
    );
}

#[tokio::test]
async fn registered_songs_changes_do_not_change_the_current_audio_round() {
    let f = Fixture::new();
    let media = f.seed();
    let mut snapshot = f.snapshot(1, "PLAYING");
    f.round(&mut snapshot);
    snapshot.rooms[0].round_number = Some(1);
    snapshot.rooms[0].total_rounds = Some(0);
    snapshot.rooms[0].registered_songs = Some(2);
    f.sync_direct(snapshot.clone(), Some(media));
    let (cookie, _) = f.login();
    let (_, _, before) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    snapshot.seq = "2".into();
    snapshot.rooms[0].room_revision = "2".into();
    snapshot.rooms[0].registered_songs = Some(3);
    snapshot.rooms[0].total_rounds = Some(200);
    let body = serde_json::to_vec(&snapshot).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &body)
        )
        .await
        .0,
        StatusCode::OK
    );
    let (_, _, after) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    let before: Value = serde_json::from_slice(&before).unwrap();
    let after: Value = serde_json::from_slice(&after).unwrap();
    assert_eq!(before["round"], after["round"]);
    assert_eq!(before["media"], after["media"]);
    assert_eq!(after["room"]["registeredSongs"], 3);
}

#[tokio::test]
async fn bridge_rejects_partial_invalid_or_noninteger_round_progress_without_accepting_state() {
    let f = Fixture::new();
    for progress in [
        json!({"roundNumber":1}),
        json!({"totalRounds":100}),
        json!({"roundNumber":1,"totalRounds":null}),
        json!({"roundNumber":null,"totalRounds":100}),
        json!({"roundNumber":-1,"totalRounds":100}),
        json!({"roundNumber":-1,"totalRounds":0}),
        json!({"roundNumber":1,"totalRounds":-1}),
        json!({"roundNumber":1,"totalRounds":2147483648_u32}),
        json!({"roundNumber":4294967295_u32,"totalRounds":2147483647_u32}),
        json!({"roundNumber":101,"totalRounds":100}),
        json!({"roundNumber":1.5,"totalRounds":100}),
        json!({"roundNumber":1,"totalRounds":100.5}),
        json!({"roundNumber":"1","totalRounds":100}),
        json!({"roundNumber":1,"totalRounds":"100"}),
        json!({"roundNumber":true,"totalRounds":100}),
        json!({"roundNumber":4294967296_u64,"totalRounds":100}),
        json!({"roundNumber":4294967296_u64,"totalRounds":0}),
    ] {
        let mut snapshot = serde_json::to_value(f.snapshot(1, "IDLE")).unwrap();
        snapshot["rooms"][0]
            .as_object_mut()
            .unwrap()
            .extend(progress.as_object().unwrap().clone());
        let body = serde_json::to_vec(&snapshot).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/sync", &body)
            )
            .await
            .0,
            StatusCode::BAD_REQUEST,
            "invalid progress {progress}"
        );
        assert!(f.app.inner.lock().unwrap().snapshot.is_none());
    }
    let mut snapshot = serde_json::to_value(f.snapshot(1, "IDLE")).unwrap();
    snapshot["rooms"][0]["roundNumber"] = Value::Null;
    snapshot["rooms"][0]["totalRounds"] = Value::Null;
    let body = serde_json::to_vec(&snapshot).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &body)
        )
        .await
        .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn room_progress_must_match_active_round_number_including_zero_boundary() {
    let f = Fixture::new();
    f.seed();
    for (number, total) in [(0, 100), (2, 100), (100, 100), (0, 0), (2, 0)] {
        let mut snapshot = f.snapshot(1, "PREPARING");
        f.round(&mut snapshot);
        snapshot.rooms[0].round_number = Some(number);
        snapshot.rooms[0].total_rounds = Some(total);
        let body = serde_json::to_vec(&snapshot).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/sync", &body)
            )
            .await
            .0,
            StatusCode::BAD_REQUEST,
            "round 1 mismatches room {number}"
        );
    }
    for (index, (number, total)) in [
        (1001, 1001),
        (i32::MAX as u32, i32::MAX as u32),
        (u32::MAX, 0),
    ]
    .into_iter()
    .enumerate()
    {
        let mut snapshot = f.snapshot(index as u64 + 1, "PREPARING");
        f.round(&mut snapshot);
        snapshot.rooms[0].round_number = Some(number);
        snapshot.rooms[0].total_rounds = Some(total);
        snapshot.rooms[0].round.as_mut().unwrap().number = number;
        let body = serde_json::to_vec(&snapshot).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/sync", &body)
            )
            .await
            .0,
            StatusCode::OK
        );
    }
}

#[tokio::test]
async fn unlimited_progress_projects_in_every_phase_without_revealing_answers_early() {
    let f = Fixture::new();
    f.seed();
    let mut initial = f.snapshot(1, "IDLE");
    initial.rooms[0].round_number = Some(0);
    initial.rooms[0].total_rounds = Some(0);
    f.sync_direct(initial, None);
    let (cookie, _) = f.login();
    for (index, (phase, number)) in [
        ("IDLE", 0),
        ("WAITING_FOR_MEDIA", 1),
        ("PREPARING", 1),
        ("COUNTDOWN", 1),
        ("PLAYING", 1),
        ("PAUSED", 1),
        ("REVEAL", 1),
        ("IDLE", 201),
        ("ENDED", u32::MAX),
    ]
    .into_iter()
    .enumerate()
    {
        let mut snapshot = f.snapshot(index as u64 + 2, phase);
        snapshot.rooms[0].round_number = Some(number);
        snapshot.rooms[0].total_rounds = Some(0);
        if ["PREPARING", "COUNTDOWN", "PLAYING", "PAUSED", "REVEAL"].contains(&phase) {
            f.round(&mut snapshot);
            snapshot.rooms[0].round.as_mut().unwrap().playing =
                matches!(phase, "COUNTDOWN" | "PLAYING");
        }
        if phase == "REVEAL" {
            snapshot.rooms[0].reveal = Some(Reveal {
                title: "정답 제목".into(),
                aliases: vec!["별칭".into()],
                tags: vec!["태그".into()],
                youtube_url: "https://www.youtube.com/watch?v=BaW_jenozKc".into(),
            });
        }
        let body = serde_json::to_vec(&snapshot).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/sync", &body)
            )
            .await
            .0,
            StatusCode::OK,
            "unlimited phase {phase}"
        );
        let (status, _, bytes) =
            call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
        assert_eq!(status, StatusCode::OK);
        let value: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["room"]["roundNumber"], number);
        assert_eq!(value["room"]["totalRounds"], 0);
        if phase == "REVEAL" {
            assert_eq!(value["reveal"]["title"], "정답 제목");
        } else {
            for secret in [
                "BaW_jenozKc",
                "youtube",
                "title",
                "aliases",
                "tags",
                "정답 제목",
                "별칭",
                "태그",
            ] {
                assert!(
                    !String::from_utf8_lossy(&bytes).contains(secret),
                    "unlimited leak in {phase}: {secret}"
                );
            }
        }
        if matches!(phase, "IDLE" | "WAITING_FOR_MEDIA" | "ENDED") {
            assert!(value["round"].is_null());
        }
    }
    f.sync_direct(f.snapshot(11, "IDLE"), None);
    let (_, _, bytes) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
    let value: Value = serde_json::from_slice(&bytes).unwrap();
    assert!(value["room"].get("roundNumber").is_none());
    assert!(value["room"].get("totalRounds").is_none());
}

#[tokio::test]
async fn live_limit_changes_update_room_progress_without_changing_audio_round() {
    let f = Fixture::new();
    f.seed();
    let mut cookie = String::new();
    let mut original_round = Value::Null;
    for (index, total) in [0, 300, 1001, i32::MAX as u32, 0, 201]
        .into_iter()
        .enumerate()
    {
        let mut snapshot = f.snapshot(index as u64 + 1, "PLAYING");
        f.round(&mut snapshot);
        snapshot.rooms[0].round_number = Some(201);
        snapshot.rooms[0].total_rounds = Some(total);
        let round = snapshot.rooms[0].round.as_mut().unwrap();
        round.number = 201;
        round.revision = "1".into();
        let body = serde_json::to_vec(&snapshot).unwrap();
        assert_eq!(
            call(
                api::internal(f.app.clone()),
                f.signed("POST", "/internal/v1/sync", &body)
            )
            .await
            .0,
            StatusCode::OK,
            "new live total {total}"
        );
        if index == 0 {
            cookie = f.login().0;
        }
        let (_, _, bytes) = call(api::public(f.app.clone()), get("/api/v1/state", &cookie)).await;
        let value: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["room"]["roundNumber"], 201);
        assert_eq!(value["room"]["totalRounds"], total);
        if index == 0 {
            original_round = value["round"].clone();
        }
        assert_eq!(value["round"], original_round);
        assert!(value["reveal"].is_null());
    }
    let mut invalid = f.snapshot(7, "PLAYING");
    f.round(&mut invalid);
    invalid.rooms[0].round_number = Some(201);
    invalid.rooms[0].total_rounds = Some(200);
    invalid.rooms[0].round.as_mut().unwrap().number = 201;
    let body = serde_json::to_vec(&invalid).unwrap();
    assert_eq!(
        call(
            api::internal(f.app.clone()),
            f.signed("POST", "/internal/v1/sync", &body)
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        f.app.inner.lock().unwrap().snapshot.as_ref().unwrap().rooms[0].total_rounds,
        Some(201)
    );
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
async fn worker_drains_all_accepted_jobs_without_a_current_round_and_preserves_ready_cache() {
    let f = Fixture::new();
    let cached = f.seed();
    let cached_path = f
        .app
        .jobs
        .root
        .join("media")
        .join(format!("{}.ogg", cached.sha256));
    let cached_bytes = std::fs::read(&cached_path).unwrap();
    let mut ids = Vec::new();
    for video in ["AAAAAAAA000", "AAAAAAAA001", "AAAAAAAA002"] {
        let (_, result) = f.app.jobs.enqueue(job(video), START).await.unwrap();
        assert_eq!(result["state"], "QUEUED");
        ids.push(result["jobId"].as_str().unwrap().to_owned());
    }
    let worker = tokio::spawn(noqui_web::jobs::worker(f.app.clone()));
    let outcome = tokio::time::timeout(std::time::Duration::from_secs(3), async {
        loop {
            let mut statuses = Vec::new();
            for id in &ids {
                statuses.push(f.app.jobs.status(id.clone()).await.unwrap());
            }
            if statuses.iter().all(|status| status["state"] == "FAILED") {
                break statuses;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    worker.abort();
    let _ = worker.await;
    for (status, id) in outcome.iter().zip(ids) {
        assert_eq!(status["failureCode"], "INGEST_UNAVAILABLE");
        assert_eq!(status["retryable"], true);
        assert!(!f.app.jobs.root.join("tmp").join(id).exists());
    }
    assert_eq!(std::fs::read(cached_path).unwrap(), cached_bytes);
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
    assert_eq!(result["profileVersion"], PROFILE);
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
            "UPDATE jobs SET profile='opus-48-clip115-v1' WHERE video=?",
            ["BaW_jenozKc"],
        )
        .unwrap();
        db.query_row("SELECT id FROM jobs WHERE video=?", ["BaW_jenozKc"], |r| {
            r.get::<_, String>(0)
        })
        .unwrap()
    };
    let old = f.app.jobs.status(old_id.clone()).await.unwrap();
    assert_eq!(old["profileVersion"], "opus-48-clip115-v1");
    assert!(f.app.jobs.media(vec![legacy.id]).await.unwrap().is_empty());
    let (pending, new) = f.app.jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap();
    assert!(pending);
    assert_eq!(new["profileVersion"], PROFILE);
    assert_eq!(new["state"], "QUEUED");
    assert_ne!(new["jobId"], old_id);
    let (_, duplicate) = f.app.jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap();
    assert_eq!(new["jobId"], duplicate["jobId"]);
}

#[tokio::test]
async fn old_five_minute_rejection_cannot_block_resubmission_under_six_minute_profile() {
    let f = Fixture::new();
    let old_id = uuid::Uuid::new_v4().to_string();
    let db = rusqlite::Connection::open(f.app.jobs.root.join("media.sqlite")).unwrap();
    db.execute("INSERT INTO jobs(id,video,profile,state,failure,retryable,updated) VALUES(?,?,'opus-48-clip115-v1','FAILED','TOO_LONG',0,?)",
        rusqlite::params![old_id, "BaW_jenozKc", START]).unwrap();
    let (pending, new) = f.app.jobs.enqueue(job("BaW_jenozKc"), START).await.unwrap();
    assert!(pending);
    assert_eq!(new["state"], "QUEUED");
    assert_eq!(new["profileVersion"], PROFILE);
    assert_ne!(new["jobId"], old_id);
}

#[tokio::test]
async fn queue_accepts_all_64_submission_slots_and_failed_jobs_require_explicit_retry() {
    let f = Fixture::new();
    for i in 0..64 {
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
    for duration in [300.0, 301.0, 359.999, 360.0] {
        assert!(noqui_web::jobs::source_duration(&json!({"duration":duration})).is_ok());
    }
    for v in [
        json!({"duration":360.001}),
        json!({"duration":361}),
        json!({"duration":0}),
        json!({"duration":100,"is_live":true}),
        json!({}),
    ] {
        assert!(noqui_web::jobs::source_duration(&v).is_err());
    }
    let good = json!({"format":{"duration":"40.0","format_name":"ogg"},"streams":[{"codec_name":"opus","channels":2,"sample_rate":"48000","tags":{"encoder":"Lavc"}}]});
    assert!(noqui_web::jobs::verify_output(&good, 1000).is_ok());
    assert!(noqui_web::jobs::verify_output(&good, noqui_web::MAX_SOURCE + 1).is_err());
    let mut leaking = good.clone();
    leaking["streams"][0]["tags"]["TITLE"] = "secret".into();
    assert!(noqui_web::jobs::verify_output(&leaking, 1000).is_err());
}

#[test]
fn encoded_cache_is_capped_at_40_seconds_while_private_source_may_be_360_seconds() {
    assert_eq!(
        noqui_web::jobs::source_duration(&json!({"duration":360})).unwrap(),
        360.0
    );
    let mut audio = json!({"format":{"duration":"40.0","format_name":"ogg"},"streams":[{"codec_name":"opus","channels":2,"sample_rate":"48000"}]});
    for duration in ["1.0", "37.25", "40.0", "40.05"] {
        audio["format"]["duration"] = duration.into();
        assert!(
            noqui_web::jobs::verify_output(&audio, 1000).is_ok(),
            "valid short/padded clip: {duration}"
        );
    }
    for duration in ["40.051", "41.0", "115.0", "360.0"] {
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
