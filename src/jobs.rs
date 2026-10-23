use crate::{
    App, CACHE_BUDGET, MAX_SOURCE, PROFILE,
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
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            for directory in [root.to_owned(), root.join("media"), root.join("tmp")] {
                fs::set_permissions(directory, fs::Permissions::from_mode(0o700))
                    .map_err(|_| "CACHE_UNAVAILABLE")?;
            }
        }
        let db = Connection::open(root.join("media.sqlite")).map_err(|_| "STORAGE_UNAVAILABLE")?;
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
        let mut missing = Vec::new();
        {
            let mut stmt = db
                .prepare("SELECT id,sha256,bytes FROM media")
                .map_err(|_| "STORAGE_UNAVAILABLE")?;
            let rows = stmt
                .query_map([], |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, u64>(2)?,
                    ))
                })
                .map_err(|_| "STORAGE_UNAVAILABLE")?;
            for row in rows.flatten() {
                if !crate::model::hex(&row.1)
                    || !fs::metadata(root.join("media").join(format!("{}.ogg", row.1)))
                        .is_ok_and(|m| m.is_file() && m.len() == row.2)
                {
                    missing.push(row.0);
                }
            }
        }
        for id in missing {
            db.execute("UPDATE jobs SET state='FAILED',failure='CACHE_MISSING',retryable=1,updated=? WHERE media_id=?",params![now,id]).map_err(|_|"STORAGE_UNAVAILABLE")?;
            db.execute("DELETE FROM media WHERE id=?", [id])
                .map_err(|_| "STORAGE_UNAVAILABLE")?;
        }
        Ok(Self {
            db: Arc::new(Mutex::new(db)),
            root: Arc::new(root.to_owned()),
            wake: Arc::new(Notify::new()),
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
        let out=self.access(move|db|{
            let tx=db.transaction().map_err(db_error)?;
            if let Some((old,id))=tx.query_row("SELECT video,job_id FROM requests WHERE id=?",[&r.request_id],|row|Ok((row.get::<_,String>(0)?,row.get::<_,String>(1)?))).optional().map_err(db_error)? {
                if old!=r.video_id {return Err(Error::new(409,"IDEMPOTENCY_CONFLICT"))}
                let value=job_json(&tx,&id)?;return Ok((value["state"]!="READY",value));
            }
            let existing:Option<(String,String,bool,i64)>=tx.query_row("SELECT id,state,retryable,updated FROM jobs WHERE video=? AND profile=?",params![r.video_id,PROFILE],|row|Ok((row.get(0)?,row.get(1)?,row.get(2)?,row.get(3)?))).optional().map_err(db_error)?;
            let pending:i64=tx.query_row("SELECT count(*) FROM jobs WHERE state='QUEUED' AND profile=?",[PROFILE],|row|row.get(0)).map_err(db_error)?;
            let id=if let Some((id,state,retryable,updated))=existing {
                if state=="FAILED" && retryable && now-updated>=30000 {
                    if pending>=MAX_QUEUED {return Err(Error::retry(429,"QUEUE_FULL",2000))}
                    tx.execute("UPDATE jobs SET state='QUEUED',failure=NULL,retryable=0,updated=? WHERE id=?",params![now,id]).map_err(db_error)?;
                }id
            } else {
                if pending>=MAX_QUEUED {return Err(Error::retry(429,"QUEUE_FULL",2000))}
                let id=uuid::Uuid::new_v4().to_string();tx.execute("INSERT INTO jobs(id,video,profile,state,updated) VALUES(?,?,?,'QUEUED',?)",params![id,r.video_id,PROFILE,now]).map_err(db_error)?;id
            };
            tx.execute("INSERT INTO requests(id,video,job_id) VALUES(?,?,?)",params![r.request_id,r.video_id,id]).map_err(db_error)?;
            // Receipts are bounded independently of deduplicated source records.
            tx.execute("DELETE FROM requests WHERE rowid IN (SELECT rowid FROM requests ORDER BY rowid DESC LIMIT -1 OFFSET 10000)",[]).map_err(db_error)?;
            let value=job_json(&tx,&id)?;tx.commit().map_err(db_error)?;Ok((value["state"]!="READY",value))
        }).await?;
        self.wake.notify_one();
        Ok(out)
    }
    pub async fn status(&self, id: String) -> Result<Value> {
        if !uuid(&id) {
            return Err(Error::new(404, "JOB_NOT_FOUND"));
        }
        self.access(move |db| job_json(db, &id)).await
    }
    pub async fn media(&self, ids: Vec<String>) -> Result<HashMap<String, Media>> {
        self.access(move|db| {let mut out=HashMap::new();for id in ids {
            if let Some(m)=db.query_row("SELECT m.id,m.sha256,m.duration,m.bytes FROM media m JOIN jobs j ON j.media_id=m.id WHERE m.id=? AND j.state='READY' AND j.profile=?",params![id,PROFILE],read_media).optional().map_err(db_error)?{out.insert(id,m);}
        }Ok(out)}).await
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
    ) -> Result<()> {
        self.access(move|db|{let tx=db.transaction().map_err(db_error)?;
            let id=tx.query_row("SELECT id FROM media WHERE sha256=?",[&sha],|r|r.get::<_,String>(0)).optional().map_err(db_error)?.unwrap_or_else(||uuid::Uuid::new_v4().to_string());
            tx.execute("INSERT OR IGNORE INTO media(id,sha256,duration,bytes) VALUES(?,?,?,?)",params![id,sha,duration,bytes]).map_err(db_error)?;
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
    let dir = app.jobs.root.join("tmp").join(&w.id);
    tokio::fs::create_dir(&dir).await.map_err(|_| "DISK_FULL")?;
    let root = app.jobs.root.clone();
    let used = tokio::task::spawn_blocking(move || directory_size(&root.join("media")))
        .await
        .map_err(|_| "DISK_FULL")??;
    // Refuse new work instead of risking eviction of an asset a room is about to select.
    if used.saturating_add(MAX_SOURCE) > CACHE_BUDGET {
        return Err("DISK_FULL");
    }
    let deadline = Instant::now() + Duration::from_secs(300);
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
        url.clone(),
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
    source_duration(&metadata)?;
    app.jobs
        .stage(w.id.clone(), "DOWNLOADING", app.now())
        .await
        .map_err(|_| "INGEST_UNAVAILABLE")?;
    let mut args = base;
    args.extend([
        "--max-filesize".into(),
        MAX_SOURCE.to_string(),
        "-f".into(),
        "bestaudio[ext=webm][abr<=80]/bestaudio[ext=webm]/bestaudio[ext=m4a]".into(),
        "-o".into(),
        dir.join("source.media").to_string_lossy().into_owned(),
        "--".into(),
        url,
    ]);
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
    let destination = app.jobs.root.join("media").join(format!("{sha}.ogg"));
    if tokio::fs::try_exists(&destination)
        .await
        .map_err(|_| "DISK_FULL")?
    {
        if tokio::fs::metadata(&destination)
            .await
            .map_err(|_| "VERIFY_FAILED")?
            .len()
            != bytes
        {
            return Err("VERIFY_FAILED");
        }
        tokio::fs::remove_file(&output)
            .await
            .map_err(|_| "DISK_FULL")?;
    } else {
        tokio::fs::rename(&output, &destination)
            .await
            .map_err(|_| "DISK_FULL")?;
    }
    if Instant::now() > deadline {
        return Err("INGEST_TIMEOUT");
    }
    app.jobs
        .commit(w.id.clone(), sha, duration, bytes, app.now())
        .await
        .map_err(|_| "INGEST_UNAVAILABLE")?;
    Ok(())
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
fn directory_size(dir: &Path) -> IngestResult<u64> {
    let mut size = 0u64;
    for entry in fs::read_dir(dir).map_err(|_| "DISK_FULL")? {
        let entry = entry.map_err(|_| "DISK_FULL")?;
        let meta = entry.metadata().map_err(|_| "DISK_FULL")?;
        if !meta.is_file() || entry.file_type().map_err(|_| "DISK_FULL")?.is_symlink() {
            return Err("DISK_FULL");
        }
        size = size.saturating_add(meta.len());
    }
    Ok(size)
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
        if (name.ends_with(".out") || name.ends_with(".err")) && bytes > 1024 * 1024 {
            return Err("TOO_LARGE");
        }
        if bytes > MAX_SOURCE || total > 64 * 1024 * 1024 {
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
        let file = fs::File::create(dir.path().join("download.err")).unwrap();
        file.set_len(1024 * 1024 + 1).unwrap();
        assert_eq!(check_temp(dir.path()).err(), Some("TOO_LARGE"));
    }
}
