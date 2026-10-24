use super::*;
use std::io::Read;

const NOW: i64 = 1_800_000_000_000;

struct Fixture {
    dir: tempfile::TempDir,
    jobs: Jobs,
}
impl Fixture {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let jobs = Jobs::open(&dir.path().join("noqui"), NOW).unwrap();
        jobs.cache
            .replace_pins(&mut jobs.db.lock().unwrap(), Some(&[]), &[])
            .unwrap();
        Self { dir, jobs }
    }
    fn seed(&self, video: &str, order: i64) -> Media {
        let bytes = video.as_bytes();
        let media = Media {
            id: uuid::Uuid::new_v4().to_string(),
            sha256: crate::auth::digest(bytes),
            duration_ms: 1000,
            byte_length: bytes.len() as u64,
        };
        fs::write(self.jobs.cache.path(&media.sha256), bytes).unwrap();
        let db = self.jobs.db.lock().unwrap();
        db.execute(
            "INSERT INTO media(id,sha256,duration,bytes,last_used) VALUES(?,?,?,?,?)",
            params![
                media.id,
                media.sha256,
                media.duration_ms,
                media.byte_length,
                order
            ],
        )
        .unwrap();
        db.execute("INSERT INTO jobs(id,video,profile,state,media_id,updated,last_used) VALUES(?,?,?,'READY',?,?,?)",params![uuid::Uuid::new_v4().to_string(),video,PROFILE,media.id,NOW,order]).unwrap();
        media
    }
    fn ready(&self, media: &Media) -> bool {
        self.jobs
            .db
            .lock()
            .unwrap()
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM media WHERE id=?)",
                [&media.id],
                |r| r.get(0),
            )
            .unwrap()
    }
    fn room(&self, incoming: u64, budget: u64, now: i64) -> Result<()> {
        self.jobs.cache.make_room_with(
            &mut self.jobs.db.lock().unwrap(),
            now,
            incoming,
            budget,
            0,
            &|| Ok(u64::MAX),
        )
    }
    fn request(video: &str) -> JobRequest {
        JobRequest {
            schema_version: 1,
            request_id: uuid::Uuid::new_v4().to_string(),
            video_id: video.into(),
            song: None,
        }
    }
}

#[tokio::test]
async fn reuse_moves_oldest_to_newest_and_idempotent_retries_do_not_touch_lru() {
    let f = Fixture::new();
    let old = f.seed("AAAAAAAAAAA", 1);
    let middle = f.seed("BBBBBBBBBBB", 2);
    let recent = f.seed("CCCCCCCCCCC", 3);
    let request = Fixture::request("AAAAAAAAAAA");
    let receipt = request.request_id.clone();
    let value = f.jobs.enqueue(request, NOW).await.unwrap().1;
    assert_eq!(value["mediaId"], old.id);
    let used: i64 = f
        .jobs
        .db
        .lock()
        .unwrap()
        .query_row("SELECT last_used FROM media WHERE id=?", [&old.id], |r| {
            r.get(0)
        })
        .unwrap();
    f.jobs
        .enqueue(
            JobRequest {
                schema_version: 1,
                request_id: receipt,
                video_id: "AAAAAAAAAAA".into(),
                song: None,
            },
            NOW + 1000,
        )
        .await
        .unwrap();
    let unchanged: i64 = f
        .jobs
        .db
        .lock()
        .unwrap()
        .query_row("SELECT last_used FROM media WHERE id=?", [&old.id], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(used, unchanged);
    f.room(11, 33, NOW + HANDOFF_MILLIS + 1).unwrap();
    assert!(f.ready(&old));
    assert!(!f.ready(&middle));
    assert!(f.ready(&recent));
    assert!(!f.jobs.cache.path(&middle.sha256).exists());
    let state: (String, Option<String>, Option<String>) = f
        .jobs
        .db
        .lock()
        .unwrap()
        .query_row(
            "SELECT state,media_id,failure FROM jobs WHERE video='BBBBBBBBBBB'",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .unwrap();
    assert_eq!(state, ("FAILED".into(), None, Some("CACHE_EVICTED".into())));
    let (_, retry) = f
        .jobs
        .enqueue(Fixture::request("BBBBBBBBBBB"), NOW + HANDOFF_MILLIS + 2)
        .await
        .unwrap();
    assert_eq!(
        retry["state"], "QUEUED",
        "evicted jobs can be re-enqueued immediately without a 30-second failure delay"
    );
}

#[tokio::test]
async fn restart_preserves_recency_and_queue_pins_but_waits_for_fresh_pin_aware_sync() {
    let f = Fixture::new();
    let a = f.seed("AAAAAAAAAAA", 1);
    let b = f.seed("BBBBBBBBBBB", 2);
    let c = f.seed("CCCCCCCCCCC", 3);
    f.jobs
        .enqueue(Fixture::request("AAAAAAAAAAA"), NOW)
        .await
        .unwrap();
    f.jobs
        .retain(Some(vec![b.id.clone()]), vec![], vec![], NOW)
        .await
        .unwrap();
    let before: i64 = f
        .jobs
        .db
        .lock()
        .unwrap()
        .query_row("SELECT last_used FROM media WHERE id=?", [&b.id], |r| {
            r.get(0)
        })
        .unwrap();
    for i in 1..=10 {
        f.jobs
            .retain(Some(vec![b.id.clone()]), vec![], vec![], NOW + i * 1000)
            .await
            .unwrap();
    }
    let after: i64 = f
        .jobs
        .db
        .lock()
        .unwrap()
        .query_row("SELECT last_used FROM media WHERE id=?", [&b.id], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(before, after);
    let reopened = Jobs::open(&f.jobs.root, NOW + HANDOFF_MILLIS + 1).unwrap();
    assert_eq!(
        reopened
            .cache
            .make_room_with(
                &mut reopened.db.lock().unwrap(),
                NOW + HANDOFF_MILLIS + 1,
                11,
                33,
                0,
                &|| Ok(u64::MAX)
            )
            .unwrap_err()
            .code,
        "DISK_FULL"
    );
    reopened
        .retain(None, vec![], vec![], NOW + HANDOFF_MILLIS + 2)
        .await
        .unwrap();
    let count: i64 = reopened
        .db
        .lock()
        .unwrap()
        .query_row(
            "SELECT count(*) FROM cache_pins WHERE kind='queue' AND media_id=?",
            [&b.id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(count, 1);
    reopened
        .retain(
            Some(vec![b.id.clone()]),
            vec![],
            vec![],
            NOW + HANDOFF_MILLIS + 3,
        )
        .await
        .unwrap();
    reopened
        .cache
        .make_room_with(
            &mut reopened.db.lock().unwrap(),
            NOW + HANDOFF_MILLIS + 3,
            11,
            33,
            0,
            &|| Ok(u64::MAX),
        )
        .unwrap();
    assert!(f.ready(&a));
    assert!(f.ready(&b));
    assert!(!f.ready(&c));
}

#[tokio::test]
async fn current_queued_and_multiple_open_streams_are_protected_until_their_owners_release() {
    let f = Fixture::new();
    let current = f.seed("AAAAAAAAAAA", 1);
    let queued = f.seed("BBBBBBBBBBB", 2);
    let streamed = f.seed("CCCCCCCCCCC", 3);
    let spare = f.seed("DDDDDDDDDDD", 4);
    f.jobs
        .retain(
            Some(vec![queued.id.clone()]),
            vec![current.id.clone()],
            vec![],
            NOW,
        )
        .await
        .unwrap();
    let (mut first, first_guard) = f
        .jobs
        .open_media(streamed.clone(), NOW, false)
        .await
        .unwrap();
    let (_, second_guard) = f
        .jobs
        .open_media(streamed.clone(), NOW, false)
        .await
        .unwrap();
    f.room(11, 44, NOW).unwrap();
    assert!(!f.ready(&spare));
    drop(first_guard);
    assert_eq!(f.room(11, 33, NOW).unwrap_err().code, "DISK_FULL");
    let mut bytes = Vec::new();
    first.read_to_end(&mut bytes).unwrap();
    assert_eq!(bytes, b"CCCCCCCCCCC");
    drop(first);
    drop(second_guard);
    f.room(11, 33, NOW).unwrap();
    assert!(!f.ready(&streamed));
    assert!(f.ready(&current));
    assert!(f.ready(&queued));
    f.jobs
        .retain(Some(vec![]), vec![], vec![], NOW)
        .await
        .unwrap();
    f.room(11, 22, NOW).unwrap();
    assert!(!f.ready(&current));
    assert!(f.ready(&queued));
}

#[tokio::test]
async fn ready_handoff_grace_and_real_media_use_are_distinct_from_pin_renewals() {
    let f = Fixture::new();
    let a = f.seed("AAAAAAAAAAA", 1);
    let b = f.seed("BBBBBBBBBBB", 2);
    f.jobs
        .enqueue(Fixture::request("AAAAAAAAAAA"), NOW)
        .await
        .unwrap();
    f.jobs
        .db
        .lock()
        .unwrap()
        .execute("UPDATE media SET last_used=0 WHERE id=?", [&a.id])
        .unwrap();
    f.room(11, 22, NOW + 1000).unwrap();
    assert!(f.ready(&a));
    assert!(!f.ready(&b));
    assert_eq!(
        f.room(11, 11, NOW + HANDOFF_MILLIS - 1).unwrap_err().code,
        "DISK_FULL"
    );
    f.room(11, 11, NOW + HANDOFF_MILLIS).unwrap();
    assert!(!f.ready(&a));
    let c = f.seed("CCCCCCCCCCC", 2);
    let d = f.seed("DDDDDDDDDDD", 3);
    let (_, guard) = f
        .jobs
        .open_media(c.clone(), NOW + HANDOFF_MILLIS + 1, true)
        .await
        .unwrap();
    drop(guard);
    f.room(11, 22, NOW + HANDOFF_MILLIS + 2).unwrap();
    assert!(f.ready(&c));
    assert!(!f.ready(&d));
}

#[test]
fn disk_pressure_reclaims_lru_even_below_cap_and_protected_full_disk_returns_backpressure() {
    let f = Fixture::new();
    let a = f.seed("AAAAAAAAAAA", 1);
    let b = f.seed("BBBBBBBBBBB", 2);
    let path = f.jobs.cache.path(&a.sha256);
    f.jobs
        .cache
        .make_room_with(&mut f.jobs.db.lock().unwrap(), NOW, 11, 1024, 16, &|| {
            Ok(if path.exists() { 5 } else { 16 })
        })
        .unwrap();
    assert!(!f.ready(&a));
    assert!(f.ready(&b));
    f.jobs
        .cache
        .replace_pins(
            &mut f.jobs.db.lock().unwrap(),
            Some(std::slice::from_ref(&b.id)),
            &[],
        )
        .unwrap();
    assert_eq!(
        f.jobs
            .cache
            .make_room_with(&mut f.jobs.db.lock().unwrap(), NOW, 11, 1024, 16, &|| Ok(0))
            .unwrap_err()
            .code,
        "DISK_FULL"
    );
    assert!(f.jobs.cache.path(&b.sha256).exists());
    assert_eq!(crate::CACHE_BUDGET, 5 * 1024 * 1024 * 1024);
}

#[test]
fn invalidation_transaction_failure_keeps_ready_file_and_record_together() {
    let f = Fixture::new();
    let a = f.seed("AAAAAAAAAAA", 1);
    f.jobs.db.lock().unwrap().execute_batch("CREATE TRIGGER reject_delete BEFORE INSERT ON cache_deletions BEGIN SELECT RAISE(ABORT,'test storage failure'); END;").unwrap();
    assert_eq!(f.room(11, 11, NOW).unwrap_err().code, "STORAGE_UNAVAILABLE");
    assert!(f.ready(&a));
    assert!(f.jobs.cache.path(&a.sha256).exists());
    let state: String = f
        .jobs
        .db
        .lock()
        .unwrap()
        .query_row("SELECT state FROM jobs WHERE media_id=?", [&a.id], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(state, "READY");
}

#[test]
fn restart_finishes_tombstoned_unlinks_and_removes_only_owned_orphans() {
    let f = Fixture::new();
    let a = f.seed("AAAAAAAAAAA", 1);
    {
        let mut db = f.jobs.db.lock().unwrap();
        let tx = db.transaction().unwrap();
        tx.execute("UPDATE jobs SET state='FAILED',media_id=NULL,failure='CACHE_EVICTED',retryable=1 WHERE media_id=?",[&a.id]).unwrap();
        tx.execute("DELETE FROM media WHERE id=?", [&a.id]).unwrap();
        tx.execute(
            "INSERT INTO cache_deletions VALUES(?,?)",
            params![a.sha256, a.byte_length],
        )
        .unwrap();
        tx.commit().unwrap();
    }
    let orphan = f
        .jobs
        .root
        .join("media")
        .join(format!("{}.ogg", "a".repeat(64)));
    fs::write(&orphan, b"uncommitted encoded output").unwrap();
    let unrelated = f.jobs.root.join("media").join("moma-note.ogg");
    fs::write(&unrelated, b"not an owned cache filename").unwrap();
    let moma = f.dir.path().join("MCLuckDefense");
    fs::create_dir_all(moma.join("cloudpacks")).unwrap();
    fs::create_dir_all(moma.join("bgm-work")).unwrap();
    let siblings = [
        moma.join("bgm.db"),
        moma.join("cloudpacks").join(format!("{}.ogg", a.sha256)),
        moma.join("bgm-work").join("source.media"),
    ];
    for path in &siblings {
        fs::write(path, b"Moma data must remain untouched").unwrap();
    }
    let reopened = Jobs::open(&f.jobs.root, NOW + 1000).unwrap();
    assert!(!f.jobs.cache.path(&a.sha256).exists());
    assert!(!orphan.exists());
    assert_eq!(fs::read(unrelated).unwrap(), b"not an owned cache filename");
    for path in siblings {
        assert_eq!(fs::read(path).unwrap(), b"Moma data must remain untouched");
    }
    let remaining: i64 = reopened
        .db
        .lock()
        .unwrap()
        .query_row("SELECT count(*) FROM cache_deletions", [], |r| r.get(0))
        .unwrap();
    assert_eq!(remaining, 0);
}

#[tokio::test]
async fn concurrent_reuse_and_eviction_never_return_ready_for_an_evicted_file() {
    let f = Fixture::new();
    let media = f.seed("AAAAAAAAAAA", 1);
    let mut tasks = Vec::new();
    for i in 0..24 {
        let jobs = f.jobs.clone();
        let cache = jobs.cache.clone();
        tasks.push(tokio::spawn(async move {
            if i % 2 == 0 {
                let value = jobs
                    .enqueue(Fixture::request("AAAAAAAAAAA"), NOW)
                    .await
                    .unwrap()
                    .1;
                Some(value)
            } else {
                let _ = jobs
                    .access(move |db| cache.make_room_with(db, NOW, 11, 11, 0, &|| Ok(u64::MAX)))
                    .await;
                None
            }
        }));
    }
    let mut ready = false;
    for task in tasks {
        if let Some(value) = task.await.unwrap()
            && value["state"] == "READY"
        {
            ready = true;
        }
    }
    if ready {
        assert!(f.jobs.cache.path(&media.sha256).exists());
        assert!(f.ready(&media));
    } else {
        assert!(!f.ready(&media));
    }
}

#[tokio::test]
async fn missing_ready_file_is_reenqueued_and_publish_failure_is_recovered_without_dangling_ready()
{
    let f = Fixture::new();
    let media = f.seed("AAAAAAAAAAA", 1);
    fs::remove_file(f.jobs.cache.path(&media.sha256)).unwrap();
    let (_, job) = f
        .jobs
        .enqueue(Fixture::request("AAAAAAAAAAA"), NOW)
        .await
        .unwrap();
    assert_eq!(job["state"], "QUEUED");
    let id = job["jobId"].as_str().unwrap().to_owned();
    let output = f.jobs.root.join("tmp").join("test-output.ogg");
    fs::write(&output, b"new output").unwrap();
    let sha = crate::auth::digest(b"new output");
    f.jobs.db.lock().unwrap().execute_batch("CREATE TRIGGER reject_publish BEFORE INSERT ON media BEGIN SELECT RAISE(ABORT,'test commit failure'); END;").unwrap();
    assert!(
        f.jobs
            .commit(id.clone(), sha.clone(), 1000, 10, NOW, output)
            .await
            .is_err()
    );
    assert_ne!(f.jobs.status(id).await.unwrap()["state"], "READY");
    assert!(
        f.jobs.cache.path(&sha).exists(),
        "an interrupted commit leaves a recoverable owned orphan"
    );
    let _reopened = Jobs::open(&f.jobs.root, NOW + 1000).unwrap();
    assert!(!f.jobs.cache.path(&sha).exists());
}

#[test]
fn legacy_database_migrates_once_without_resetting_newer_use_order() {
    let dir = tempfile::tempdir().unwrap();
    fs::create_dir(dir.path().join("media")).unwrap();
    fs::create_dir(dir.path().join("tmp")).unwrap();
    let hash = crate::auth::digest(b"old");
    fs::write(dir.path().join("media").join(format!("{hash}.ogg")), b"old").unwrap();
    let db = Connection::open(dir.path().join("media.sqlite")).unwrap();
    db.execute_batch("CREATE TABLE media(id TEXT PRIMARY KEY,sha256 TEXT UNIQUE,duration INTEGER,bytes INTEGER);CREATE TABLE jobs(id TEXT PRIMARY KEY,video TEXT,profile TEXT,state TEXT,media_id TEXT,failure TEXT,retryable INTEGER DEFAULT 0,updated INTEGER,UNIQUE(video,profile));").unwrap();
    db.execute("INSERT INTO media VALUES('legacy',?,1000,3)", [&hash])
        .unwrap();
    db.execute("INSERT INTO jobs(id,video,profile,state,media_id,updated) VALUES('legacy-job','AAAAAAAAAAA',?,'READY','legacy',123)",[PROFILE]).unwrap();
    drop(db);
    let jobs = Jobs::open(dir.path(), NOW).unwrap();
    assert_eq!(
        jobs.db
            .lock()
            .unwrap()
            .query_row("SELECT last_used FROM media", [], |r| r.get::<_, i64>(0))
            .unwrap(),
        123
    );
    Cache::touch(&jobs.db.lock().unwrap(), "legacy", NOW).unwrap();
    drop(jobs);
    let jobs = Jobs::open(dir.path(), NOW + 1000).unwrap();
    assert_eq!(
        jobs.db
            .lock()
            .unwrap()
            .query_row("SELECT last_used FROM media", [], |r| r.get::<_, i64>(0))
            .unwrap(),
        NOW
    );
}

#[cfg(unix)]
#[test]
fn cache_directories_cannot_redirect_into_a_sibling_moma_cache() {
    use std::os::unix::fs::symlink;
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("noqui");
    let other = dir.path().join("MCLuckDefense");
    fs::create_dir(&root).unwrap();
    fs::create_dir(&other).unwrap();
    fs::write(other.join("keep.ogg"), b"untouched").unwrap();
    symlink(&other, root.join("media")).unwrap();
    assert!(Jobs::open(&root, NOW).is_err());
    assert_eq!(fs::read(other.join("keep.ogg")).unwrap(), b"untouched");
}

#[tokio::test]
async fn cancelled_older_sync_completion_cannot_overwrite_newer_pins_or_recency() {
    let f = Fixture::new();
    let old = f.seed("AAAAAAAAAAA", 1);
    let latest = f.seed("BBBBBBBBBBB", 2);
    let (_, old_guard) = f.jobs.protect(vec![old.id.clone()]).await.unwrap();
    let (_, new_guard) = f.jobs.protect(vec![latest.id.clone()]).await.unwrap();
    f.jobs
        .retain_selection(
            Some(vec![latest.id.clone()]),
            vec![],
            vec![],
            NOW,
            Some(new_guard),
            Some(PinVersion {
                revision: 2,
                instance: "current-instance".into(),
                seq: "20".into(),
            }),
        )
        .await
        .unwrap();
    f.jobs
        .retain_selection(
            Some(vec![old.id.clone()]),
            vec![],
            vec![old.id.clone()],
            NOW + 1,
            Some(old_guard),
            Some(PinVersion {
                revision: 1,
                instance: "current-instance".into(),
                seq: "19".into(),
            }),
        )
        .await
        .unwrap();
    let row: (String, String) = f
        .jobs
        .db
        .lock()
        .unwrap()
        .query_row("SELECT instance_id,seq FROM cache_control", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .unwrap();
    assert_eq!(row, ("current-instance".into(), "20".into()));
    let order: i64 = f
        .jobs
        .db
        .lock()
        .unwrap()
        .query_row("SELECT last_used FROM media WHERE id=?", [&old.id], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(order, 1);
    f.room(11, 22, NOW + 2).unwrap();
    assert!(!f.ready(&old));
    assert!(f.ready(&latest));
}

#[tokio::test]
async fn cache_eviction_and_restart_preserve_private_song_catalogue_metadata() {
    let f = Fixture::new();
    let media = f.seed("AAAAAAAAAAA", 1);
    let mut request = Fixture::request("AAAAAAAAAAA");
    let id = request.request_id.clone();
    request.song = Some(crate::catalogue::SongMetadata {
        title: "Persistent Song".into(),
        tags: vec!["Retained Tag".into()],
    });
    assert_eq!(
        f.jobs.enqueue(request, NOW).await.unwrap().1["state"],
        "READY"
    );
    f.room(11, 11, NOW + HANDOFF_MILLIS + 1).unwrap();
    assert!(!f.ready(&media));
    let reopened = Jobs::open(&f.jobs.root, NOW + HANDOFF_MILLIS + 2).unwrap();
    let record: (String, String, String) = reopened
        .db
        .lock()
        .unwrap()
        .query_row(
            "SELECT youtube_url,title,tags_json FROM song_catalogue WHERE id=?",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .unwrap();
    assert_eq!(record.0, "https://www.youtube.com/watch?v=AAAAAAAAAAA");
    assert_eq!(record.1, "Persistent Song");
    assert_eq!(
        serde_json::from_str::<Value>(&record.2).unwrap(),
        json!(["Retained Tag"])
    );
}

#[tokio::test]
async fn http_stream_holds_its_media_pin_until_body_drop_even_after_control_releases_the_round() {
    use axum::{body::Body, http::Request};
    use http_body_util::BodyExt;
    use tower::ServiceExt;
    struct Clock;
    impl crate::Clock for Clock {
        fn now_ms(&self) -> i64 {
            NOW
        }
    }
    let f = Fixture::new();
    let media = f.seed("AAAAAAAAAAA", 1);
    let mut app = App::open(
        crate::Config {
            secret: [7; 32],
            key_id: "primary".into(),
            data: f.jobs.root.as_ref().clone(),
            yt_dlp: "unused".into(),
            ffmpeg: "unused".into(),
            ffprobe: "unused".into(),
            cookies: None,
        },
        Arc::new(Clock),
    )
    .unwrap();
    app.jobs = f.jobs.clone();
    let instance = uuid::Uuid::new_v4().to_string();
    let room = uuid::Uuid::new_v4().to_string();
    let player = uuid::Uuid::new_v4().to_string();
    let member = uuid::Uuid::new_v4().to_string();
    let snapshot:crate::model::Snapshot=serde_json::from_value(json!({"schemaVersion":1,"instanceId":instance,"seq":"1","sentAtMs":NOW,"roster":[{"uuid":player,"membershipId":member,"nickname":"Test","textureHash":null,"roomId":room}],"rooms":[{"roomId":room,"roomRevision":"1","phase":"PREPARING","round":{"roundId":uuid::Uuid::new_v4().to_string(),"number":1,"revision":"1","playbackGeneration":uuid::Uuid::new_v4().to_string(),"mediaId":media.id,"clipStartMs":0,"clipEndMs":1000,"anchorPositionMs":0,"anchorAtMs":NOW+3000,"playing":false},"reveal":null}]})).unwrap();
    let cookie = {
        let mut state = app.inner.lock().unwrap();
        state
            .sync(
                snapshot,
                "hash".into(),
                HashMap::from([(media.id.clone(), media.clone())]),
                NOW,
            )
            .unwrap();
        let token = crate::auth::random();
        state
            .register(
                crate::model::TokenRequest {
                    schema_version: 1,
                    request_id: uuid::Uuid::new_v4().to_string(),
                    instance_id: instance,
                    uuid: player,
                    membership_id: member,
                    room_id: room,
                    token_sha256: crate::auth::digest(token.as_bytes()),
                },
                "request".into(),
                NOW,
            )
            .unwrap();
        let (session, _) = state.redeem(&token, NOW).unwrap();
        format!("__Host-noqui={session}")
    };
    let request = || {
        Request::builder()
            .uri(format!("/media/{}.ogg", media.sha256))
            .header("cookie", &cookie)
            .body(Body::empty())
            .unwrap()
    };
    let response = crate::api::public(app.clone())
        .oneshot(request())
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    // No persisted current-round pin: the unpolled HTTP response itself must retain the file.
    assert_eq!(f.room(11, 11, NOW).unwrap_err().code, "DISK_FULL");
    drop(response);
    let response = crate::api::public(app).oneshot(request()).await.unwrap();
    let mut body = response.into_body();
    assert_eq!(
        body.frame()
            .await
            .unwrap()
            .unwrap()
            .into_data()
            .unwrap()
            .as_ref(),
        b"AAAAAAAAAAA"
    );
    assert_eq!(f.room(11, 11, NOW).unwrap_err().code, "DISK_FULL");
    assert!(body.frame().await.is_none());
    f.room(11, 11, NOW).unwrap();
    assert!(!f.ready(&media));
}
