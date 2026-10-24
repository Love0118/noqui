use crate::{
    App, MAX_SOURCE, PROFILE,
    cache::{Cache, HANDOFF_MILLIS, MediaPins, PinVersion, TEMP_BUDGET},
    catalogue::{self, CatalogueImportRequest, SongRecord},
    model::{Error, JobRequest, Result, uuid, video},
};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    process::Stdio,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tokio::{process::Command, sync::Notify};

const MAX_QUEUED: i64 = 64;
// Stage logs stay tiny, but yt-dlp's resolved metadata for a private source can
// legitimately exceed 1MiB (a real 99s webm resolved to 1,660,825 bytes), so the
// metadata dump gets its own headroom well below the source and temp budgets.
const LOG_OUTPUT_LIMIT: u64 = 1024 * 1024;
const METADATA_OUTPUT_LIMIT: u64 = 8 * 1024 * 1024;
#[cfg(test)]
#[path = "cache_tests.rs"]
mod cache_tests;
const SOURCE_FORMAT: &str = "bestaudio[ext=webm][abr<=80]/bestaudio[ext=webm]/bestaudio[ext=m4a]";

#[derive(Clone, Serialize, Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Media {
    pub id: String,
    pub sha256: String,
    pub duration_ms: i64,
    pub byte_length: u64,
}
#[derive(Clone)]
pub struct Jobs {
    db: Arc<Mutex<Connection>>,
    pub root: Arc<PathBuf>,
    wake: Arc<Notify>,
    cache: Arc<Cache>,
}
#[derive(Clone)]
struct Work {
    id: String,
    video: String,
}
fn db_error(_: rusqlite::Error) -> Error {
    Error::new(500, "STORAGE_UNAVAILABLE")
}
impl Jobs {
    pub fn open(root: &Path, now: i64) -> std::result::Result<Self, String> {
        fs::create_dir_all(root.join("media")).map_err(|_| "CACHE_UNAVAILABLE")?;
        fs::create_dir_all(root.join("tmp")).map_err(|_| "CACHE_UNAVAILABLE")?;
        Cache::validate_paths(root)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            for directory in [root.to_owned(), root.join("media"), root.join("tmp")] {
                fs::set_permissions(directory, fs::Permissions::from_mode(0o700))
                    .map_err(|_| "CACHE_UNAVAILABLE")?;
            }
        }
        let mut db =
            Connection::open(root.join("media.sqlite")).map_err(|_| "STORAGE_UNAVAILABLE")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(root.join("media.sqlite"), fs::Permissions::from_mode(0o600))
                .map_err(|_| "STORAGE_UNAVAILABLE")?;
        }
        db.busy_timeout(Duration::from_secs(2))
            .map_err(|_| "STORAGE_UNAVAILABLE")?;
        db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
          CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,video TEXT NOT NULL,profile TEXT NOT NULL,state TEXT NOT NULL,media_id TEXT,failure TEXT,retryable INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL,UNIQUE(video,profile));
          CREATE TABLE IF NOT EXISTS media(id TEXT PRIMARY KEY,sha256 TEXT NOT NULL UNIQUE,duration INTEGER NOT NULL,bytes INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY,video TEXT NOT NULL,job_id TEXT NOT NULL);").map_err(|_|"STORAGE_UNAVAILABLE")?;
        catalogue::initialize(&db).map_err(|error| error.code)?;
        db.execute("UPDATE jobs SET state='FAILED',failure='INGEST_INTERRUPTED',retryable=1,updated=? WHERE state NOT IN ('QUEUED','READY','FAILED')",[now]).map_err(|_|"STORAGE_UNAVAILABLE")?;
        // Temp directories contain only generated UUID names. No partial output is reusable after restart.
        for entry in fs::read_dir(root.join("tmp"))
            .map_err(|_| "CACHE_UNAVAILABLE")?
            .flatten()
        {
            if uuid(&entry.file_name().to_string_lossy())
                && entry
                    .file_type()
                    .is_ok_and(|t| t.is_dir() && !t.is_symlink())
            {
                let _ = fs::remove_dir_all(entry.path());
            }
        }
        let root = Arc::new(root.to_owned());
        let cache = Cache::new(root.clone());
        cache.initialize(&mut db, now).map_err(|e| e.code)?;
        Ok(Self {
            db: Arc::new(Mutex::new(db)),
            root,
            wake: Arc::new(Notify::new()),
            cache,
        })
    }
    async fn access<T: Send + 'static>(
        &self,
        f: impl FnOnce(&mut Connection) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        let db = self.db.clone();
        tokio::task::spawn_blocking(move || f(&mut db.lock().unwrap()))
            .await
            .map_err(|_| Error::new(500, "STORAGE_UNAVAILABLE"))?
    }
    pub async fn enqueue(&self, r: JobRequest, now: i64) -> Result<(bool, Value)> {
        if r.schema_version != 1 || !uuid(&r.request_id) || !video(&r.video_id) {
            return Err(Error::new(422, "INVALID_SOURCE"));
        }
        let song = r
            .song
            .as_ref()
            .map(|metadata| SongRecord::from_job(&r.request_id, &r.video_id, metadata))
            .transpose()?;
        let cache = self.cache.clone();
        let out=self.access(move|db|{
            let existing_id: Option<String> = db.query_row("SELECT id FROM jobs WHERE video=? AND profile=?", params![r.video_id, PROFILE], |row| row.get(0)).optional().map_err(db_error)?;
            if let Some(id) = &existing_id { cache.reconcile_job(db, id, now)?; }
            let tx=db.transaction().map_err(db_error)?;
            if let Some((old,id))=tx.query_row("SELECT video,job_id FROM requests WHERE id=?",[&r.request_id],|row|Ok((row.get::<_,String>(0)?,row.get::<_,String>(1)?))).optional().map_err(db_error)? {
                if old!=r.video_id {return Err(Error::new(409,"IDEMPOTENCY_CONFLICT"))}
                if let Some(song)=&song { catalogue::save(&tx,song,now)?; }
                let value=job_json(&tx,&id)?;tx.commit().map_err(db_error)?;return Ok((value["state"]!="READY",value));
            }
            if let Some(song)=&song { catalogue::save(&tx,song,now)?; }
            let existing:Option<(String,String,bool,i64,Option<String>)>=tx.query_row("SELECT id,state,retryable,updated,failure FROM jobs WHERE video=? AND profile=?",params![r.video_id,PROFILE],|row|Ok((row.get(0)?,row.get(1)?,row.get(2)?,row.get(3)?,row.get(4)?))).optional().map_err(db_error)?;
            let pending:i64=tx.query_row("SELECT count(*) FROM jobs WHERE state='QUEUED' AND profile=?",[PROFILE],|row|row.get(0)).map_err(db_error)?;
            let id=if let Some((id,state,retryable,updated,failure))=existing {
                if state=="FAILED" && retryable && (now-updated>=30000 || matches!(failure.as_deref(), Some("CACHE_EVICTED" | "CACHE_MISSING"))) {
                    if pending>=MAX_QUEUED {return Err(Error::retry(429,"QUEUE_FULL",2000))}
                    tx.execute("UPDATE jobs SET state='QUEUED',media_id=NULL,failure=NULL,retryable=0,updated=? WHERE id=?",params![now,id]).map_err(db_error)?;
                }id
            } else {
                if pending>=MAX_QUEUED {return Err(Error::retry(429,"QUEUE_FULL",2000))}
                let id=uuid::Uuid::new_v4().to_string();tx.execute("INSERT INTO jobs(id,video,profile,state,updated) VALUES(?,?,?,'QUEUED',?)",params![id,r.video_id,PROFILE,now]).map_err(db_error)?;id
            };
            tx.execute("INSERT INTO requests(id,video,job_id) VALUES(?,?,?)",params![r.request_id,r.video_id,id]).map_err(db_error)?;
            let order = Cache::next_use(&tx, now)?;
            tx.execute("UPDATE jobs SET last_used=? WHERE id=?",params![order,id]).map_err(db_error)?;
            tx.execute("UPDATE media SET last_used=?,handoff_until=MAX(handoff_until,?) WHERE id=(SELECT media_id FROM jobs WHERE id=? AND state='READY')",params![order,now+HANDOFF_MILLIS,id]).map_err(db_error)?;
            // Receipts are bounded independently of deduplicated source records.
            tx.execute("DELETE FROM requests WHERE rowid IN (SELECT rowid FROM requests ORDER BY rowid DESC LIMIT -1 OFFSET 10000)",[]).map_err(db_error)?;
            let value=job_json(&tx,&id)?;tx.commit().map_err(db_error)?;Ok((value["state"]!="READY",value))
        }).await?;
        self.wake.notify_one();
        Ok(out)
    }
    pub async fn import_catalogue(
        &self,
        request: CatalogueImportRequest,
        now: i64,
    ) -> Result<Value> {
        request.validate()?;
        self.access(move |db| {
            let tx = db.transaction().map_err(db_error)?;
            for song in &request.songs {
                catalogue::save(&tx, song, now)?;
            }
            tx.commit().map_err(db_error)?;
            Ok(json!({"savedCount":request.songs.len()}))
        })
        .await
    }
    pub async fn status(&self, id: String) -> Result<Value> {
        if !uuid(&id) {
            return Err(Error::new(404, "JOB_NOT_FOUND"));
        }
        let cache = self.cache.clone();
        self.access(move |db| {
            let value = job_json(db, &id)?;
            if value["state"] == "READY" {
                let media = value["mediaId"]
                    .as_str()
                    .ok_or(Error::new(404, "MEDIA_NOT_FOUND"))?;
                let record = db
                    .query_row("SELECT sha256,bytes FROM media WHERE id=?", [media], |r| {
                        Ok((r.get::<_, String>(0)?, r.get::<_, u64>(1)?))
                    })
                    .optional()
                    .map_err(db_error)?;
                let valid = record.is_some_and(|(sha, bytes)| cache.valid_file(&sha, bytes));
                if !valid {
                    return Err(Error::new(404, "MEDIA_NOT_FOUND"));
                }
            }
            Ok(value)
        })
        .await
    }
    pub async fn media(&self, ids: Vec<String>) -> Result<HashMap<String, Media>> {
        Ok(self.protect(ids).await?.0)
    }
    pub async fn protect(&self, ids: Vec<String>) -> Result<(HashMap<String, Media>, MediaPins)> {
        let cache = self.cache.clone();
        self.access(move |db| {
            let mut out = HashMap::new();
            for id in ids {
                if let Some(m) = db.query_row("SELECT m.id,m.sha256,m.duration,m.bytes FROM media m JOIN jobs j ON j.media_id=m.id WHERE m.id=? AND j.state='READY' AND j.profile=?", params![id,PROFILE],read_media).optional().map_err(db_error)?.filter(|m|cache.valid_file(&m.sha256,m.byte_length)) {
                    out.insert(id,m);
                }
            }
            let guard = cache.pin(out.keys().cloned().collect());
            Ok((out,guard))
        }).await
    }
    pub async fn retain(
        &self,
        retained: Option<Vec<String>>,
        current: Vec<String>,
        newly_used: Vec<String>,
        now: i64,
    ) -> Result<()> {
        self.retain_selection(retained, current, newly_used, now, None, None)
            .await
    }
    pub(crate) async fn retain_selection(
        &self,
        retained: Option<Vec<String>>,
        current: Vec<String>,
        newly_used: Vec<String>,
        now: i64,
        selection: Option<MediaPins>,
        version: Option<PinVersion>,
    ) -> Result<()> {
        let cache = self.cache.clone();
        self.access(move |db| {
            // The blocking transaction retains its guard even if the HTTP future is cancelled.
            let _selection = selection;
            let applied = if version.is_some() {
                cache.replace_versioned_pins(db, retained.as_deref(), &current, version.as_ref())?
            } else {
                cache.replace_pins(db, retained.as_deref(), &current)?;
                true
            };
            if applied {
                for id in newly_used {
                    Cache::touch(db, &id, now)?;
                }
            }
            Ok(())
        })
        .await
    }
    pub async fn open_media(
        &self,
        media: Media,
        now: i64,
        touch: bool,
    ) -> Result<(fs::File, MediaPins)> {
        let cache = self.cache.clone();
        self.access(move |db| {
            let registered: bool = db
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM media WHERE id=? AND sha256=? AND bytes=?)",
                    params![media.id, media.sha256, media.byte_length],
                    |r| r.get(0),
                )
                .map_err(db_error)?;
            if !registered || !cache.valid_file(&media.sha256, media.byte_length) {
                return Err(Error::new(404, "MEDIA_NOT_FOUND"));
            }
            let file = fs::File::open(cache.path(&media.sha256))
                .map_err(|_| Error::new(404, "MEDIA_NOT_FOUND"))?;
            let guard = cache.pin(vec![media.id.clone()]);
            if touch {
                Cache::touch(db, &media.id, now)?;
            }
            Ok((file, guard))
        })
        .await
    }
    async fn reserve_cache(&self, now: i64) -> Result<()> {
        let cache = self.cache.clone();
        self.access(move |db| cache.make_room(db, now, MAX_SOURCE))
            .await
    }
    async fn claim(&self, now: i64) -> Result<Option<Work>> {
        self.access(move |db| {
            let tx = db.transaction().map_err(db_error)?;
            let row = tx
                .query_row(
                    "SELECT id,video FROM jobs WHERE state='QUEUED' AND profile=? ORDER BY updated LIMIT 1",
                    [PROFILE],
                    |r| {
                        Ok(Work {
                            id: r.get(0)?,
                            video: r.get(1)?,
                        })
                    },
                )
                .optional()
                .map_err(db_error)?;
            if let Some(w) = &row {
                tx.execute(
                    "UPDATE jobs SET state='RESOLVING',updated=? WHERE id=?",
                    params![now, w.id],
                )
                .map_err(db_error)?;
            }
            tx.commit().map_err(db_error)?;
            Ok(row)
        })
        .await
    }
    async fn stage(&self, id: String, stage: &'static str, now: i64) -> Result<()> {
        self.access(move |db| {
            db.execute(
                "UPDATE jobs SET state=?,updated=? WHERE id=?",
                params![stage, now, id],
            )
            .map_err(db_error)?;
            Ok(())
        })
        .await
    }
    pub async fn fail(
        &self,
        id: String,
        code: &'static str,
        retryable: bool,
        now: i64,
    ) -> Result<()> {
        self.access(move |db| {
            db.execute(
                "UPDATE jobs SET state='FAILED',failure=?,retryable=?,updated=? WHERE id=?",
                params![code, retryable, now, id],
            )
            .map_err(db_error)?;
            Ok(())
        })
        .await
    }
    async fn commit(
        &self,
        job: String,
        sha: String,
        duration: i64,
        bytes: u64,
        now: i64,
        output: PathBuf,
    ) -> Result<()> {
        let cache = self.cache.clone();
        self.access(move|db|{let tx=db.transaction().map_err(db_error)?;
            let destination = cache.path(&sha);
            if destination.try_exists().map_err(|_|Error::new(507,"DISK_FULL"))? {
                if !cache.valid_file(&sha,bytes) { return Err(Error::new(500,"VERIFY_FAILED")); }
                fs::remove_file(output).map_err(|_|Error::new(507,"DISK_FULL"))?;
            } else { fs::rename(output,&destination).map_err(|_|Error::new(507,"DISK_FULL"))?; }
            #[cfg(unix)]
            fs::File::open(destination.parent().unwrap()).and_then(|f|f.sync_all()).map_err(|_|Error::new(507,"DISK_FULL"))?;
            let id=tx.query_row("SELECT id FROM media WHERE sha256=?",[&sha],|r|r.get::<_,String>(0)).optional().map_err(db_error)?.unwrap_or_else(||uuid::Uuid::new_v4().to_string());
            let order: i64 = tx.query_row("SELECT last_used FROM jobs WHERE id=?",[&job],|r|r.get(0)).map_err(db_error)?;
            tx.execute("INSERT INTO media(id,sha256,duration,bytes,last_used,handoff_until) VALUES(?,?,?,?,?,?) ON CONFLICT(sha256) DO UPDATE SET last_used=MAX(last_used,excluded.last_used),handoff_until=MAX(handoff_until,excluded.handoff_until)",params![id,sha,duration,bytes,order,now+HANDOFF_MILLIS]).map_err(db_error)?;
            tx.execute("UPDATE jobs SET state='READY',media_id=?,failure=NULL,retryable=0,updated=? WHERE id=?",params![id,now,job]).map_err(db_error)?;
            tx.commit().map_err(db_error)?;Ok(())
        }).await
    }
}
fn read_media(r: &rusqlite::Row<'_>) -> rusqlite::Result<Media> {
    Ok(Media {
        id: r.get(0)?,
        sha256: r.get(1)?,
        duration_ms: r.get(2)?,
        byte_length: r.get(3)?,
    })
}
fn job_json(db: &Connection, id: &str) -> Result<Value> {
    type JobRow = (String, Option<String>, Option<String>, bool, String);
    let row: Option<JobRow> = db
        .query_row(
            "SELECT state,media_id,failure,retryable,profile FROM jobs WHERE id=?",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .optional()
        .map_err(db_error)?;
    let (state, media_id, failure, retryable, profile) =
        row.ok_or(Error::new(404, "JOB_NOT_FOUND"))?;
    let media = if let Some(id) = &media_id {
        db.query_row(
            "SELECT id,sha256,duration,bytes FROM media WHERE id=?",
            [id],
            read_media,
        )
        .optional()
        .map_err(db_error)?
    } else {
        None
    };
    Ok(
        json!({"jobId":id,"state":state,"mediaId":if state=="READY"{media_id}else{None},"profileVersion":profile,"durationMs":media.as_ref().map(|m|m.duration_ms),"byteLength":media.as_ref().map(|m|m.byte_length),"mime":if state=="READY"{Some("audio/ogg; codecs=opus")}else{None},"failureCode":failure,"retryable":retryable,"retryAfterMs":if state=="READY"{0}else if state=="FAILED"{30000}else{2000}}),
    )
}

pub async fn worker(app: App) {
    loop {
        match app.jobs.claim(app.now()).await {
            Ok(Some(work)) => {
                let result = tokio::time::timeout(Duration::from_secs(300), ingest(&app, &work))
                    .await
                    .unwrap_or(Err("INGEST_TIMEOUT"));
                let dir = app.jobs.root.join("tmp").join(&work.id);
                let _ = tokio::fs::remove_dir_all(dir).await;
                if let Err(code) = result {
                    let retry = !matches!(code, "TOO_LONG" | "TOO_LARGE" | "FORMAT_UNSUPPORTED");
                    let _ = app.jobs.fail(work.id.clone(), code, retry, app.now()).await;
                }
                app.notify();
            }
            _ => {
                tokio::select! {_=app.jobs.wake.notified()=>{},_=tokio::time::sleep(Duration::from_secs(1))=>{}}
            }
        }
    }
}
type IngestResult<T> = std::result::Result<T, &'static str>;
async fn ingest(app: &App, w: &Work) -> IngestResult<()> {
    let mut temporary = tokio::fs::read_dir(app.jobs.root.join("tmp"))
        .await
        .map_err(|_| "DISK_FULL")?;
    if temporary
        .next_entry()
        .await
        .map_err(|_| "DISK_FULL")?
        .is_some()
    {
        return Err("DISK_FULL");
    }
    app.jobs
        .reserve_cache(app.now())
        .await
        .map_err(|e| e.code)?;
    let dir = app.jobs.root.join("tmp").join(&w.id);
    tokio::fs::create_dir(&dir).await.map_err(|_| "DISK_FULL")?;
    let deadline = Instant::now() + Duration::from_secs(300);
    let ffmpeg = executable_path(&app.config.ffmpeg)?;
    let url = format!("https://www.youtube.com/watch?v={}", w.video);
    let mut base = vec![
        "--ignore-config".into(),
        "--use-extractors".into(),
        "youtube".into(),
        "--js-runtimes".into(),
        "node".into(),
        "--no-playlist".into(),
        "--socket-timeout".into(),
        "15".into(),
        "--retries".into(),
        "1".into(),
        "--fragment-retries".into(),
        "1".into(),
        "--no-progress".into(),
        "-f".into(),
        SOURCE_FORMAT.into(),
    ];
    if let Some(path) = &app.config.cookies {
        if !path.is_file() {
            return Err("SOURCE_AUTH_REQUIRED");
        }
        base.extend(["--cookies".into(), path.to_string_lossy().into_owned()]);
    }
    let mut args = base.clone();
    args.extend([
        "--skip-download".into(),
        "--dump-single-json".into(),
        "--".into(),
        url,
    ]);
    run(
        &app.config.yt_dlp,
        &args,
        &dir,
        "metadata",
        deadline.min(Instant::now() + Duration::from_secs(40)),
    )
    .await?;
    let metadata: Value = serde_json::from_slice(
        &tokio::fs::read(dir.join("metadata.out"))
            .await
            .map_err(|_| "SOURCE_UNAVAILABLE")?,
    )
    .map_err(|_| "SOURCE_UNAVAILABLE")?;
    let metadata = download_info(metadata, &w.video)?;
    let info_path = dir.join("resolved.json");
    tokio::fs::write(
        &info_path,
        serde_json::to_vec(&metadata).map_err(|_| "SOURCE_UNAVAILABLE")?,
    )
    .await
    .map_err(|_| "DISK_FULL")?;
    app.jobs
        .stage(w.id.clone(), "DOWNLOADING", app.now())
        .await
        .map_err(|_| "INGEST_UNAVAILABLE")?;
    let mut args = base;
    args.extend([
        "--max-filesize".into(),
        MAX_SOURCE.to_string(),
        "--load-info-json".into(),
        info_path.to_string_lossy().into_owned(),
        "-o".into(),
        dir.join("source.media").to_string_lossy().into_owned(),
    ]);
    if source_duration(&metadata)? > 115.0 {
        args.extend([
            "--download-sections".into(),
            "*0-115".into(),
            "--ffmpeg-location".into(),
            ffmpeg.to_string_lossy().into_owned(),
        ]);
    }
    run(
        &app.config.yt_dlp,
        &args,
        &dir,
        "download",
        deadline.min(Instant::now() + Duration::from_secs(150)),
    )
    .await?;
    let source = dir.join("source.media");
    let size = tokio::fs::metadata(&source)
        .await
        .map_err(|_| "SOURCE_UNAVAILABLE")?
        .len();
    if size == 0 || size > MAX_SOURCE {
        return Err("TOO_LARGE");
    }
    let source_info = probe(app, &source, &dir, "sourceprobe", deadline).await?;
    if !source_info["format"]["format_name"]
        .as_str()
        .is_some_and(|s| s == "matroska,webm" || s == "mov,mp4,m4a,3gp,3g2,mj2")
    {
        return Err("FORMAT_UNSUPPORTED");
    }
    let source_seconds = source_info["format"]["duration"]
        .as_str()
        .and_then(|s| s.parse::<f64>().ok())
        .ok_or("SOURCE_UNAVAILABLE")?;
    if !source_seconds.is_finite() || source_seconds <= 0.0 || source_seconds > 300.05 {
        return Err("TOO_LONG");
    }
    app.jobs
        .stage(w.id.clone(), "ENCODING", app.now())
        .await
        .map_err(|_| "INGEST_UNAVAILABLE")?;
    let output = dir.join("audio.ogg");
    let args = vec![
        "-hide_banner",
        "-nostdin",
        "-v",
        "error",
        "-y",
        "-protocol_whitelist",
        "file,pipe",
        "-i",
    ]
    .into_iter()
    .map(String::from)
    .chain([source.to_string_lossy().into_owned()])
    .chain(
        [
            "-map",
            "0:a:0",
            "-map_metadata",
            "-1",
            "-map_metadata:s:a",
            "-1",
            "-map_chapters",
            "-1",
            "-vn",
            "-sn",
            "-dn",
            "-c:a",
            "libopus",
            "-b:a",
            "48k",
            "-vbr",
            "constrained",
            "-ac",
            "2",
            "-ar",
            "48000",
            "-application",
            "audio",
            "-compression_level",
            "8",
            "-threads",
            "1",
            "-t",
            "115",
        ]
        .into_iter()
        .map(String::from),
    )
    .chain([output.to_string_lossy().into_owned()])
    .collect::<Vec<_>>();
    run(&app.config.ffmpeg, &args, &dir, "encode", deadline).await?;
    app.jobs
        .stage(w.id.clone(), "VERIFYING", app.now())
        .await
        .map_err(|_| "INGEST_UNAVAILABLE")?;
    let encoded = probe(app, &output, &dir, "verify", deadline).await?;
    let (duration, bytes) = verify_output(
        &encoded,
        tokio::fs::metadata(&output)
            .await
            .map_err(|_| "VERIFY_FAILED")?
            .len(),
    )?;
    let output_clone = output.clone();
    let sha = tokio::task::spawn_blocking(move || -> IngestResult<String> {
        use std::io::Read;
        let mut file = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(output_clone)
            .map_err(|_| "VERIFY_FAILED")?;
        file.sync_all().map_err(|_| "DISK_FULL")?;
        let mut hash = Sha256::new();
        let mut chunk = [0u8; 65536];
        loop {
            let n = file.read(&mut chunk).map_err(|_| "VERIFY_FAILED")?;
            if n == 0 {
                break;
            }
            hash.update(&chunk[..n]);
        }
        Ok(format!("{:x}", hash.finalize()))
    })
    .await
    .map_err(|_| "VERIFY_FAILED")??;
    if Instant::now() > deadline {
        return Err("INGEST_TIMEOUT");
    }
    app.jobs
        .commit(w.id.clone(), sha, duration, bytes, app.now(), output)
        .await
        .map_err(|error| error.code)?;
    Ok(())
}
fn download_info(mut info: Value, video: &str) -> IngestResult<Value> {
    source_duration(&info)?;
    if info["id"].as_str() != Some(video)
        || info
            .get("_type")
            .is_some_and(|value| value.as_str() != Some("video"))
    {
        return Err("SOURCE_UNAVAILABLE");
    }
    let object = info.as_object_mut().ok_or("SOURCE_UNAVAILABLE")?;
    // yt-dlp otherwise retries a failed info-json download by extracting this
    // URL again. Keep failure bounded; an explicit job retry resolves fresh URLs.
    object.remove("webpage_url");
    object.remove("original_url");
    // Caption tracks are unused by an audio-only download and carry short-lived signed
    // URLs, so they are dropped before the info is written back for the download stage.
    object.remove("automatic_captions");
    object.remove("subtitles");
    Ok(info)
}
fn executable_path(program: &str) -> IngestResult<PathBuf> {
    let path = Path::new(program);
    if path
        .parent()
        .is_some_and(|parent| !parent.as_os_str().is_empty())
    {
        return if path.is_file() {
            fs::canonicalize(path).map_err(|_| "INGEST_UNAVAILABLE")
        } else {
            Err("INGEST_UNAVAILABLE")
        };
    }
    for directory in std::env::split_paths(&std::env::var_os("PATH").unwrap_or_default()) {
        let candidate = directory.join(program);
        if candidate.is_file() {
            return fs::canonicalize(candidate).map_err(|_| "INGEST_UNAVAILABLE");
        }
        #[cfg(windows)]
        if path.extension().is_none() {
            let candidate = candidate.with_extension("exe");
            if candidate.is_file() {
                return fs::canonicalize(candidate).map_err(|_| "INGEST_UNAVAILABLE");
            }
        }
    }
    Err("INGEST_UNAVAILABLE")
}
pub fn source_duration(info: &Value) -> IngestResult<f64> {
    let duration = info["duration"].as_f64().ok_or("SOURCE_UNAVAILABLE")?;
    if info["is_live"].as_bool() == Some(true)
        || !duration.is_finite()
        || duration <= 0.0
        || duration > 300.0
    {
        return Err("TOO_LONG");
    }
    Ok(duration)
}
pub fn verify_output(info: &Value, bytes: u64) -> IngestResult<(i64, u64)> {
    let stream = info["streams"]
        .as_array()
        .and_then(|a| a.first())
        .ok_or("VERIFY_FAILED")?;
    let duration = info["format"]["duration"]
        .as_str()
        .and_then(|s| s.parse::<f64>().ok())
        .ok_or("VERIFY_FAILED")?;
    if bytes == 0
        || bytes > MAX_SOURCE
        || !duration.is_finite()
        || duration <= 0.0
        || duration > 115.05
        || info["format"]["format_name"] != "ogg"
        || stream["codec_name"] != "opus"
        || stream["channels"] != 2
        || stream["sample_rate"] != "48000"
        || info["streams"].as_array().is_none_or(|a| a.len() != 1)
    {
        return Err("VERIFY_FAILED");
    }
    for tags in [&info["format"]["tags"], &stream["tags"]] {
        if tags
            .as_object()
            .is_some_and(|tags| tags.keys().any(|k| !k.eq_ignore_ascii_case("encoder")))
        {
            return Err("VERIFY_FAILED");
        }
    }
    Ok(((duration * 1000.0).round() as i64, bytes))
}
async fn probe(
    app: &App,
    path: &Path,
    dir: &Path,
    label: &str,
    deadline: Instant,
) -> IngestResult<Value> {
    let args = [
        "-v",
        "error",
        "-protocol_whitelist",
        "file,pipe",
        "-show_format",
        "-show_streams",
        "-of",
        "json",
    ]
    .into_iter()
    .map(String::from)
    .chain([path.to_string_lossy().into_owned()])
    .collect::<Vec<_>>();
    run(
        &app.config.ffprobe,
        &args,
        dir,
        label,
        deadline.min(Instant::now() + Duration::from_secs(15)),
    )
    .await?;
    serde_json::from_slice(
        &tokio::fs::read(dir.join(format!("{label}.out")))
            .await
            .map_err(|_| "VERIFY_FAILED")?,
    )
    .map_err(|_| "VERIFY_FAILED")
}
fn check_temp(dir: &Path) -> IngestResult<()> {
    let mut total = 0u64;
    let mut count = 0;
    for entry in fs::read_dir(dir).map_err(|_| "DISK_FULL")? {
        let entry = entry.map_err(|_| "DISK_FULL")?;
        count += 1;
        if !entry.file_type().map_err(|_| "DISK_FULL")?.is_file() || count > 64 {
            return Err("TOO_LARGE");
        }
        let bytes = entry.metadata().map_err(|_| "DISK_FULL")?.len();
        total = total.saturating_add(bytes);
        let name = entry.file_name().to_string_lossy().into_owned();
        // Every stage shares this directory, so the wider metadata cap is keyed on the
        // file name instead of the stage that happens to be running.
        let limit = if name == "metadata.out" {
            METADATA_OUTPUT_LIMIT
        } else {
            LOG_OUTPUT_LIMIT
        };
        if (name.ends_with(".out") || name.ends_with(".err")) && bytes > limit {
            return Err("TOO_LARGE");
        }
        if bytes > MAX_SOURCE || total > TEMP_BUDGET {
            return Err("TOO_LARGE");
        }
    }
    Ok(())
}
async fn run(
    program: &str,
    args: &[String],
    dir: &Path,
    label: &str,
    deadline: Instant,
) -> IngestResult<()> {
    let started = Instant::now();
    let out = fs::File::create(dir.join(format!("{label}.out"))).map_err(|_| "DISK_FULL")?;
    let err = fs::File::create(dir.join(format!("{label}.err"))).map_err(|_| "DISK_FULL")?;
    let mut command = Command::new(program);
    command
        .args(args)
        .stdin(Stdio::null())
        .stdout(out)
        .stderr(err)
        .kill_on_drop(true);
    #[cfg(unix)]
    {
        command.process_group(0);
        // A failed downloader cannot grow one output past the hard source limit.
        unsafe {
            command.pre_exec(|| {
                let limit = libc::rlimit {
                    rlim_cur: MAX_SOURCE as libc::rlim_t,
                    rlim_max: MAX_SOURCE as libc::rlim_t,
                };
                if libc::setrlimit(libc::RLIMIT_FSIZE, &limit) != 0 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(())
            });
        }
    }
    #[cfg(windows)]
    {
        command.creation_flags(0x08000000);
    }
    let mut child = command.spawn().map_err(|_| "INGEST_UNAVAILABLE")?;
    let pid = child.id();
    let mut cancellation = ProcessGroup { pid, armed: true };
    let result = loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                break if status.success() {
                    check_temp(dir)
                } else {
                    Err(classify(&dir.join(format!("{label}.err"))))
                };
            }
            Err(_) => break Err("INGEST_UNAVAILABLE"),
            _ => {}
        }
        if Instant::now() >= deadline {
            break Err("INGEST_TIMEOUT");
        }
        if let Err(code) = check_temp(dir) {
            break Err(code);
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    };
    if result.is_err() {
        #[cfg(unix)]
        if let Some(pid) = pid {
            unsafe {
                libc::kill(-(pid as i32), libc::SIGKILL);
            }
        }
        #[cfg(windows)]
        if let Some(pid) = pid {
            let _ = Command::new("taskkill")
                .args(["/PID", &pid.to_string(), "/T", "/F"])
                .creation_flags(0x08000000)
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .await;
        }
        let _ = child.kill().await;
    }
    let _ = child.wait().await;
    cancellation.armed = false;
    if let Some(job) = dir
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|id| uuid(id))
    {
        eprintln!(
            "{}",
            json!({"event":"media_stage","jobId":job,"stage":label,"elapsedMs":started.elapsed().as_millis(),"result":result.as_ref().err().copied().unwrap_or("OK")})
        );
    }
    result
}
struct ProcessGroup {
    pid: Option<u32>,
    armed: bool,
}
impl Drop for ProcessGroup {
    fn drop(&mut self) {
        if !self.armed {
            return;
        }
        #[cfg(unix)]
        if let Some(pid) = self.pid {
            unsafe {
                libc::kill(-(pid as i32), libc::SIGKILL);
            }
        }
        #[cfg(windows)]
        if let Some(pid) = self.pid {
            use std::os::windows::process::CommandExt;
            let _ = std::process::Command::new("taskkill")
                .args(["/PID", &pid.to_string(), "/T", "/F"])
                .creation_flags(0x08000000)
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
        }
    }
}
fn classify(path: &Path) -> &'static str {
    let text = fs::read(path).unwrap_or_default();
    let text = String::from_utf8_lossy(&text[..text.len().min(65536)]).to_ascii_lowercase();
    if text.contains("not a bot") {
        "SOURCE_BLOCKED"
    } else if text.contains("cookies")
        || text.contains("sign in")
        || text.contains("confirm your age")
    {
        "SOURCE_AUTH_REQUIRED"
    } else if text.contains("larger than")
        || text.contains("max-filesize")
        || text.contains("file too large")
    {
        "TOO_LARGE"
    } else {
        "SOURCE_UNAVAILABLE"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn owned_child_processes_stop_on_deadline_and_task_cancellation() {
        fn stopped(pid: i32) -> bool {
            if unsafe { libc::kill(pid, 0) } != 0 {
                return true;
            }
            fs::read_to_string(format!("/proc/{pid}/stat")).is_ok_and(|stat| {
                stat.rsplit_once(") ")
                    .is_some_and(|(_, tail)| tail.starts_with('Z'))
            })
        }
        for cancel in [false, true] {
            let dir = tempfile::tempdir().unwrap();
            let child_file = dir.path().join("owned-child.pid");
            let work_dir = dir.path().to_owned();
            let args = vec![
                "-c".into(),
                "sleep 30 & printf '%s' \"$!\" > \"$1\"; wait".into(),
                "fixture".into(),
                child_file.to_string_lossy().into_owned(),
            ];
            let task = tokio::spawn(async move {
                run(
                    "/bin/sh",
                    &args,
                    &work_dir,
                    "child-cleanup",
                    Instant::now() + Duration::from_millis(if cancel { 30000 } else { 750 }),
                )
                .await
            });
            let child: i32 = tokio::time::timeout(Duration::from_secs(3), async {
                loop {
                    if let Ok(text) = fs::read_to_string(&child_file)
                        && let Ok(pid) = text.parse()
                    {
                        break pid;
                    }
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            })
            .await
            .unwrap();
            if cancel {
                task.abort();
                assert!(task.await.unwrap_err().is_cancelled());
            } else {
                assert_eq!(task.await.unwrap().err(), Some("INGEST_TIMEOUT"));
            }
            tokio::time::timeout(Duration::from_secs(2), async {
                while !stopped(child) {
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            })
            .await
            .expect("owned child must not survive its job");
        }
    }
    #[test]
    fn resolved_download_keeps_selected_formats_but_cannot_reextract_a_webpage() {
        let source = json!({"id":"BaW_jenozKc","_type":"video","duration":240,
            "webpage_url":"https://www.youtube.com/watch?v=BaW_jenozKc",
            "original_url":"https://www.youtube.com/watch?v=BaW_jenozKc",
            "subtitles":{"en":[{"ext":"vtt","url":"https://media.example.test/signed-sub"}]},
            "automatic_captions":{"en":[{"ext":"json3","url":"https://media.example.test/signed-auto"}]},
            "formats":[{"format_id":"249","acodec":"opus","vcodec":"none","abr":64,"url":"https://media.example.test/audio"}]});
        let ready = download_info(source.clone(), "BaW_jenozKc").unwrap();
        assert_eq!(ready["formats"], source["formats"]);
        assert_eq!(ready["duration"], 240);
        assert!(ready.get("webpage_url").is_none());
        assert!(ready.get("original_url").is_none());
        assert!(ready.get("subtitles").is_none());
        assert!(ready.get("automatic_captions").is_none());
        assert_eq!(
            download_info(source.clone(), "AAAAAAAAAAA").err(),
            Some("SOURCE_UNAVAILABLE")
        );
        let mut playlist = source.clone();
        playlist["_type"] = json!("playlist");
        assert_eq!(
            download_info(playlist, "BaW_jenozKc").err(),
            Some("SOURCE_UNAVAILABLE")
        );
        let mut live = source.clone();
        live["is_live"] = json!(true);
        assert_eq!(download_info(live, "BaW_jenozKc").err(), Some("TOO_LONG"));
        let mut long = source;
        long["duration"] = json!(301);
        assert_eq!(download_info(long, "BaW_jenozKc").err(), Some("TOO_LONG"));
    }
    #[tokio::test]
    async fn subprocess_deadline_stops_work_and_reports_timeout() {
        let dir = tempfile::tempdir().unwrap();
        #[cfg(windows)]
        let (program, args) = (
            format!(
                "{}\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                std::env::var("SystemRoot").unwrap()
            ),
            vec![
                "-NoProfile".into(),
                "-NonInteractive".into(),
                "-Command".into(),
                "Start-Sleep -Seconds 30".into(),
            ],
        );
        #[cfg(unix)]
        let (program, args) = ("/bin/sh".to_owned(), vec!["-c".into(), "sleep 30".into()]);
        let start = Instant::now();
        assert_eq!(
            run(
                &program,
                &args,
                dir.path(),
                "deadline",
                start + Duration::from_millis(250)
            )
            .await
            .err(),
            Some("INGEST_TIMEOUT")
        );
        assert!(start.elapsed() < Duration::from_secs(5));
    }
    #[test]
    fn temporary_output_caps_reject_large_files_and_logs() {
        let dir = tempfile::tempdir().unwrap();
        let file = fs::File::create(dir.path().join("source.media")).unwrap();
        file.set_len(MAX_SOURCE + 1).unwrap();
        assert_eq!(check_temp(dir.path()).err(), Some("TOO_LARGE"));
        drop(file);
        fs::remove_file(dir.path().join("source.media")).unwrap();
        for name in [
            "download.err",
            "download.out",
            "sourceprobe.out",
            "encode.out",
            "metadata.err",
        ] {
            let file = fs::File::create(dir.path().join(name)).unwrap();
            file.set_len(LOG_OUTPUT_LIMIT).unwrap();
            assert_eq!(check_temp(dir.path()).ok(), Some(()), "{name} at the cap");
            file.set_len(LOG_OUTPUT_LIMIT + 1).unwrap();
            assert_eq!(
                check_temp(dir.path()).err(),
                Some("TOO_LARGE"),
                "{name} over the cap"
            );
            drop(file);
            fs::remove_file(dir.path().join(name)).unwrap();
        }
    }
    #[test]
    fn metadata_dump_of_a_real_song_survives_the_temp_cap() {
        let dir = tempfile::tempdir().unwrap();
        let file = fs::File::create(dir.path().join("metadata.out")).unwrap();
        // Measured yt-dlp metadata for the rejected 99s private webm source.
        file.set_len(1_660_825).unwrap();
        assert_eq!(check_temp(dir.path()).ok(), Some(()));
        file.set_len(METADATA_OUTPUT_LIMIT).unwrap();
        assert_eq!(check_temp(dir.path()).ok(), Some(()));
        file.set_len(METADATA_OUTPUT_LIMIT + 1).unwrap();
        assert_eq!(check_temp(dir.path()).err(), Some("TOO_LARGE"));
        drop(file);
        fs::remove_file(dir.path().join("metadata.out")).unwrap();
    }
    #[test]
    fn shared_temp_budget_survives_the_wider_metadata_cap() {
        let dir = tempfile::tempdir().unwrap();
        // Every file stays inside its own limit; only the directory total crosses TEMP_BUDGET.
        let fill = |name: &str, bytes: u64| {
            fs::File::create(dir.path().join(name))
                .unwrap()
                .set_len(bytes)
                .unwrap();
        };
        fill("metadata.out", METADATA_OUTPUT_LIMIT);
        fill("source.media", MAX_SOURCE);
        fill("resolved.json", MAX_SOURCE);
        for name in [
            "download.err",
            "download.out",
            "encode.err",
            "encode.out",
            "metadata.err",
            "sourceprobe.err",
            "sourceprobe.out",
        ] {
            fill(name, LOG_OUTPUT_LIMIT);
        }
        assert_eq!(check_temp(dir.path()).err(), Some("TOO_LARGE"));
    }
}
