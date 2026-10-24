use crate::model::{Error, Result, hex};
use rusqlite::{Connection, OptionalExtension, params};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};

pub(crate) const TEMP_BUDGET: u64 = 64 * 1024 * 1024;
const FREE_RESERVE: u64 = TEMP_BUDGET + 64 * 1024 * 1024;
pub(crate) const HANDOFF_MILLIS: i64 = 60_000;

fn storage(_: rusqlite::Error) -> Error {
    Error::new(500, "STORAGE_UNAVAILABLE")
}
fn full() -> Error {
    Error::retry(507, "DISK_FULL", 30000)
}

/// Every method that reads or changes cache ownership runs under Jobs' database mutex.
/// Stream guards only decrement their own reference counts when dropped.
pub(crate) struct Cache {
    root: Arc<PathBuf>,
    leases: Mutex<HashMap<String, usize>>,
    eviction_enabled: AtomicBool,
    pin_revision: AtomicU64,
}
pub(crate) struct PinVersion {
    pub revision: u64,
    pub instance: String,
    pub seq: String,
}
pub struct MediaPins {
    cache: Arc<Cache>,
    ids: Vec<String>,
}
impl Drop for MediaPins {
    fn drop(&mut self) {
        let mut leases = self.cache.leases.lock().unwrap();
        for id in &self.ids {
            if let Some(count) = leases.get_mut(id) {
                *count -= 1;
                if *count == 0 {
                    leases.remove(id);
                }
            }
        }
    }
}

impl Cache {
    pub(crate) fn new(root: Arc<PathBuf>) -> Arc<Self> {
        Arc::new(Self {
            root,
            leases: Mutex::new(HashMap::new()),
            // A fresh authoritative pin set is required after every process restart.
            eviction_enabled: AtomicBool::new(false),
            pin_revision: AtomicU64::new(0),
        })
    }
    pub(crate) fn validate_paths(root: &Path) -> std::result::Result<(), String> {
        let canonical = root.canonicalize().map_err(|_| "CACHE_UNAVAILABLE")?;
        for name in ["media", "tmp"] {
            let path = root.join(name);
            let metadata = fs::symlink_metadata(&path).map_err(|_| "CACHE_UNAVAILABLE")?;
            if !metadata.is_dir()
                || metadata.file_type().is_symlink()
                || path.canonicalize().map_err(|_| "CACHE_UNAVAILABLE")? != canonical.join(name)
            {
                return Err("CACHE_UNAVAILABLE".into());
            }
        }
        for name in ["media.sqlite", "media.sqlite-wal", "media.sqlite-shm"] {
            if fs::symlink_metadata(root.join(name))
                .is_ok_and(|m| !m.is_file() || m.file_type().is_symlink())
            {
                return Err("STORAGE_UNAVAILABLE".into());
            }
        }
        Ok(())
    }
    pub(crate) fn initialize(&self, db: &mut Connection, now: i64) -> Result<()> {
        let tx = db.transaction().map_err(storage)?;
        for (table, column) in [
            ("media", "last_used"),
            ("media", "handoff_until"),
            ("jobs", "last_used"),
        ] {
            let present = tx
                .prepare(&format!("PRAGMA table_info({table})"))
                .map_err(storage)?
                .query_map([], |row| row.get::<_, String>(1))
                .map_err(storage)?
                .collect::<std::result::Result<Vec<_>, _>>()
                .map_err(storage)?
                .iter()
                .any(|name| name == column);
            if !present {
                tx.execute_batch(&format!(
                    "ALTER TABLE {table} ADD COLUMN {column} INTEGER NOT NULL DEFAULT 0"
                ))
                .map_err(storage)?;
            }
        }
        tx.execute_batch("CREATE TABLE IF NOT EXISTS cache_clock(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS cache_pins(media_id TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(media_id,kind));
            CREATE TABLE IF NOT EXISTS cache_deletions(sha256 TEXT PRIMARY KEY,bytes INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS cache_control(id INTEGER PRIMARY KEY CHECK(id=1),instance_id TEXT NOT NULL,seq TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS media_last_used ON media(last_used,id);
            UPDATE jobs SET last_used=updated WHERE last_used=0;
            UPDATE media SET last_used=COALESCE((SELECT MAX(last_used) FROM jobs WHERE media_id=media.id),0) WHERE last_used=0;
            INSERT OR IGNORE INTO cache_clock VALUES(1,0);
            UPDATE cache_clock SET value=MAX(value,COALESCE((SELECT MAX(last_used) FROM jobs),0),COALESCE((SELECT MAX(last_used) FROM media),0));")
            .map_err(storage)?;
        tx.commit().map_err(storage)?;
        self.recover(db, now)
    }
    pub(crate) fn next_use(db: &Connection, now: i64) -> Result<i64> {
        db.query_row(
            "UPDATE cache_clock SET value=MAX(value+1,?) WHERE id=1 RETURNING value",
            [now],
            |r| r.get(0),
        )
        .map_err(storage)
    }
    pub(crate) fn touch(db: &Connection, id: &str, now: i64) -> Result<()> {
        let order = Self::next_use(db, now)?;
        db.execute(
            "UPDATE media SET last_used=? WHERE id=?",
            params![order, id],
        )
        .map_err(storage)?;
        Ok(())
    }
    pub(crate) fn pin(self: &Arc<Self>, ids: Vec<String>) -> MediaPins {
        let mut leases = self.leases.lock().unwrap();
        for id in &ids {
            *leases.entry(id.clone()).or_default() += 1;
        }
        MediaPins {
            cache: self.clone(),
            ids,
        }
    }
    pub(crate) fn replace_pins(
        &self,
        db: &mut Connection,
        retained: Option<&[String]>,
        current: &[String],
    ) -> Result<()> {
        self.replace_versioned_pins(db, retained, current, None)
            .map(|_| ())
    }
    pub(crate) fn replace_versioned_pins(
        &self,
        db: &mut Connection,
        retained: Option<&[String]>,
        current: &[String],
        version: Option<&PinVersion>,
    ) -> Result<bool> {
        if let Some(version) = version {
            if version.revision < self.pin_revision.load(Ordering::SeqCst) {
                return Ok(false);
            }
            self.pin_revision.store(version.revision, Ordering::SeqCst);
        }
        self.eviction_enabled.store(false, Ordering::SeqCst);
        let tx = db.transaction().map_err(storage)?;
        tx.execute("DELETE FROM cache_pins WHERE kind='current'", [])
            .map_err(storage)?;
        for id in current {
            tx.execute(
                "INSERT OR IGNORE INTO cache_pins(media_id,kind) VALUES(?,'current')",
                [id],
            )
            .map_err(storage)?;
        }
        if let Some(ids) = retained {
            tx.execute("DELETE FROM cache_pins WHERE kind='queue'", [])
                .map_err(storage)?;
            for id in ids {
                tx.execute(
                    "INSERT OR IGNORE INTO cache_pins(media_id,kind) VALUES(?,'queue')",
                    [id],
                )
                .map_err(storage)?;
            }
        }
        if let Some(version) = version {
            tx.execute("INSERT INTO cache_control(id,instance_id,seq) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET instance_id=excluded.instance_id,seq=excluded.seq",params![version.instance,version.seq]).map_err(storage)?;
        }
        tx.commit().map_err(storage)?;
        self.eviction_enabled
            .store(retained.is_some(), Ordering::SeqCst);
        Ok(true)
    }
    pub(crate) fn valid_file(&self, sha: &str, bytes: u64) -> bool {
        hex(sha)
            && fs::symlink_metadata(self.path(sha))
                .is_ok_and(|m| m.is_file() && !m.file_type().is_symlink() && m.len() == bytes)
    }
    pub(crate) fn path(&self, sha: &str) -> PathBuf {
        self.root.join("media").join(format!("{sha}.ogg"))
    }
    fn invalidate(db: &Connection, id: &str, now: i64, reason: &str) -> Result<()> {
        db.execute("UPDATE jobs SET state='FAILED',media_id=NULL,failure=?,retryable=1,updated=? WHERE media_id=?", params![reason, now, id]).map_err(storage)?;
        db.execute("DELETE FROM media WHERE id=?", [id])
            .map_err(storage)?;
        db.execute("DELETE FROM cache_pins WHERE media_id=?", [id])
            .map_err(storage)?;
        Ok(())
    }
    pub(crate) fn reconcile_job(&self, db: &mut Connection, id: &str, now: i64) -> Result<()> {
        let record: Option<(String, String, u64)> = db.query_row(
            "SELECT m.id,m.sha256,m.bytes FROM jobs j JOIN media m ON m.id=j.media_id WHERE j.id=? AND j.state='READY'",
            [id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?))).optional().map_err(storage)?;
        if let Some((media, sha, bytes)) = record {
            if !self.valid_file(&sha, bytes) {
                let tx = db.transaction().map_err(storage)?;
                Self::invalidate(&tx, &media, now, "CACHE_MISSING")?;
                tx.commit().map_err(storage)?;
            }
        } else {
            db.execute("UPDATE jobs SET state='FAILED',media_id=NULL,failure='CACHE_MISSING',retryable=1,updated=? WHERE id=? AND state='READY'", params![now, id]).map_err(storage)?;
        }
        Ok(())
    }
    fn delete_retired(&self, db: &Connection) -> Result<()> {
        let hashes = db
            .prepare("SELECT sha256 FROM cache_deletions ORDER BY sha256")
            .map_err(storage)?
            .query_map([], |r| r.get::<_, String>(0))
            .map_err(storage)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(storage)?;
        for sha in hashes {
            if !hex(&sha) {
                return Err(full());
            }
            let path = self.path(&sha);
            match fs::symlink_metadata(&path) {
                Ok(metadata) if metadata.is_file() && !metadata.file_type().is_symlink() => {
                    fs::remove_file(path).map_err(|_| full())?
                }
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                _ => return Err(full()),
            }
            db.execute("DELETE FROM cache_deletions WHERE sha256=?", [sha])
                .map_err(storage)?;
        }
        Ok(())
    }
    fn recover(&self, db: &mut Connection, now: i64) -> Result<()> {
        self.delete_retired(db)?;
        let records = db
            .prepare("SELECT id,sha256,bytes FROM media")
            .map_err(storage)?
            .query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, u64>(2)?,
                ))
            })
            .map_err(storage)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(storage)?;
        let tx = db.transaction().map_err(storage)?;
        for (id, sha, bytes) in records {
            if !self.valid_file(&sha, bytes) {
                Self::invalidate(&tx, &id, now, "CACHE_MISSING")?;
            }
        }
        tx.commit().map_err(storage)?;
        // Only generated SHA256 .ogg names inside this cache are owned. Unknown files and siblings stay untouched.
        for entry in fs::read_dir(self.root.join("media")).map_err(|_| full())? {
            let entry = entry.map_err(|_| full())?;
            let name = entry.file_name().to_string_lossy().into_owned();
            let Some(sha) = name.strip_suffix(".ogg").filter(|s| hex(s)) else {
                continue;
            };
            if !entry.file_type().map_err(|_| full())?.is_file() {
                continue;
            }
            let exists: bool = db
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM media WHERE sha256=?)",
                    [sha],
                    |r| r.get(0),
                )
                .map_err(storage)?;
            if !exists {
                fs::remove_file(entry.path()).map_err(|_| full())?;
            }
        }
        Ok(())
    }
    pub(crate) fn make_room(&self, db: &mut Connection, now: i64, incoming: u64) -> Result<()> {
        self.make_room_with(
            db,
            now,
            incoming,
            crate::CACHE_BUDGET,
            FREE_RESERVE,
            &|| available_space(&self.root).map_err(|_| full()),
        )
    }
    pub(crate) fn make_room_with(
        &self,
        db: &mut Connection,
        now: i64,
        incoming: u64,
        budget: u64,
        reserve: u64,
        available: &dyn Fn() -> Result<u64>,
    ) -> Result<()> {
        self.recover(db, now)?;
        let mut used: u64 = db
            .query_row("SELECT COALESCE(SUM(bytes),0) FROM media", [], |r| r.get(0))
            .map_err(storage)?;
        let candidates = db.prepare("SELECT id,sha256,bytes FROM media WHERE handoff_until<=? AND NOT EXISTS(SELECT 1 FROM cache_pins WHERE media_id=media.id) ORDER BY last_used,id").map_err(storage)?
            .query_map([now], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, u64>(2)?))).map_err(storage)?
            .collect::<std::result::Result<Vec<_>, _>>().map_err(storage)?;
        for (id, sha, bytes) in candidates {
            if used.saturating_add(incoming) <= budget && available()? >= reserve {
                return Ok(());
            }
            if !self.eviction_enabled.load(Ordering::SeqCst) {
                return Err(full());
            }
            if self.leases.lock().unwrap().contains_key(&id) {
                continue;
            }
            // Commit invalidation before unlink. Restart can finish an interrupted unlink, never expose READY without its file.
            let tx = db.transaction().map_err(storage)?;
            Self::invalidate(&tx, &id, now, "CACHE_EVICTED")?;
            tx.execute(
                "INSERT INTO cache_deletions(sha256,bytes) VALUES(?,?)",
                params![sha, bytes],
            )
            .map_err(storage)?;
            tx.commit().map_err(storage)?;
            self.delete_retired(db)?;
            used = used.saturating_sub(bytes);
        }
        if used.saturating_add(incoming) <= budget && available()? >= reserve {
            Ok(())
        } else {
            Err(full())
        }
    }
}

#[cfg(unix)]
fn available_space(path: &Path) -> std::io::Result<u64> {
    use std::{ffi::CString, mem::MaybeUninit, os::unix::ffi::OsStrExt};
    let path = CString::new(path.as_os_str().as_bytes())?;
    let mut stat = MaybeUninit::<libc::statvfs>::uninit();
    if unsafe { libc::statvfs(path.as_ptr(), stat.as_mut_ptr()) } != 0 {
        return Err(std::io::Error::last_os_error());
    }
    let stat = unsafe { stat.assume_init() };
    Ok(stat.f_bavail.saturating_mul(stat.f_frsize))
}
#[cfg(windows)]
fn available_space(path: &Path) -> std::io::Result<u64> {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetDiskFreeSpaceExW(
            path: *const u16,
            available: *mut u64,
            total: *mut u64,
            free: *mut u64,
        ) -> i32;
    }
    let path: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let mut available = 0;
    if unsafe {
        GetDiskFreeSpaceExW(
            path.as_ptr(),
            &mut available,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
        )
    } == 0
    {
        return Err(std::io::Error::last_os_error());
    }
    Ok(available)
}
