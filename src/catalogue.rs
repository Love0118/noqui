//! Private submission metadata, independent of the evictable media cache.
//!
//! The caller owns the database connection and transaction. This module never
//! starts or commits a transaction and does not expose a public HTTP endpoint.

use crate::model::{self, Error, Result};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};

const YOUTUBE_PREFIX: &str = "https://www.youtube.com/watch?v=";
pub const MAX_IMPORT_SONGS: usize = 50;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SongMetadata {
    pub title: String,
    pub tags: Vec<String>,
}

impl SongMetadata {
    pub fn validate(&self) -> Result<()> {
        validate_metadata(&self.title, &self.tags)
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SongRecord {
    pub id: String,
    pub youtube_url: String,
    pub title: String,
    pub tags: Vec<String>,
}

impl SongRecord {
    pub fn from_job(request_id: &str, video_id: &str, metadata: &SongMetadata) -> Result<Self> {
        if !model::uuid(request_id) || !model::video(video_id) {
            return Err(Error::new(422, "INVALID_SOURCE"));
        }
        metadata.validate()?;
        Ok(Self {
            id: request_id.to_owned(),
            youtube_url: format!("{YOUTUBE_PREFIX}{video_id}"),
            title: metadata.title.clone(),
            tags: metadata.tags.clone(),
        })
    }

    /// Validate imported records as well as records built directly by callers.
    pub fn validate(&self) -> Result<()> {
        if !model::uuid(&self.id)
            || !self
                .youtube_url
                .strip_prefix(YOUTUBE_PREFIX)
                .is_some_and(model::video)
        {
            return Err(Error::new(422, "INVALID_SOURCE"));
        }
        validate_metadata(&self.title, &self.tags)
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CatalogueImportRequest {
    pub schema_version: u32,
    pub songs: Vec<SongRecord>,
}

impl CatalogueImportRequest {
    /// Call before opening the import transaction. An empty import is a no-op.
    pub fn validate(&self) -> Result<()> {
        if self.schema_version != 1 || self.songs.len() > MAX_IMPORT_SONGS {
            return Err(model::bad());
        }
        for song in &self.songs {
            song.validate()?;
        }
        Ok(())
    }
}

fn validate_metadata(title: &str, tags: &[String]) -> Result<()> {
    if !valid_text(title, 160) || tags.len() > 10 || tags.iter().any(|tag| !valid_text(tag, 32)) {
        return Err(Error::new(422, "INVALID_SONG_METADATA"));
    }
    Ok(())
}

fn valid_text(value: &str, max_scalars: usize) -> bool {
    // Java Song already emits trimmed NFC text and deduplicated tags. Validate
    // the wire payload without rewriting it: exact-payload retries stay exact.
    !value.is_empty()
        && value.trim_matches(|c| c <= '\u{0020}') == value
        && value.chars().count() <= max_scalars
        && !value.chars().any(char::is_control)
        && value
            .chars()
            .any(|c| c.is_ascii_alphanumeric() || ('\u{ac00}'..='\u{d7a3}').contains(&c))
}

pub(crate) fn initialize(db: &Connection) -> Result<()> {
    // Deliberately no references to jobs/media/requests: cache eviction and
    // receipt pruning must not delete the private submission catalogue.
    db.execute(
        "CREATE TABLE IF NOT EXISTS song_catalogue (
            id TEXT PRIMARY KEY NOT NULL,
            youtube_url TEXT NOT NULL,
            title TEXT NOT NULL,
            tags_json TEXT NOT NULL,
            created INTEGER NOT NULL,
            updated INTEGER NOT NULL
        )",
        [],
    )
    .map_err(db_error)?;
    Ok(())
}

/// Insert within the caller's transaction. Exact retries do not change timestamps.
/// Returns true only for a new submission; a conflicting UUID is never overwritten.
pub(crate) fn save(db: &Connection, song: &SongRecord, now: i64) -> Result<bool> {
    song.validate()?;
    let tags_json =
        serde_json::to_string(&song.tags).map_err(|_| Error::new(500, "STORAGE_UNAVAILABLE"))?;
    let inserted = db
        .execute(
            "INSERT INTO song_catalogue (id, youtube_url, title, tags_json, created, updated)
             VALUES (?1, ?2, ?3, ?4, ?5, ?5)
             ON CONFLICT(id) DO NOTHING",
            params![song.id, song.youtube_url, song.title, tags_json, now],
        )
        .map_err(db_error)?;
    if inserted == 1 {
        return Ok(true);
    }
    let existing: Option<(String, String, String)> = db
        .query_row(
            "SELECT youtube_url, title, tags_json FROM song_catalogue WHERE id = ?1",
            [&song.id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()
        .map_err(db_error)?;
    let (url, title, tags) = existing.ok_or(Error::new(500, "STORAGE_UNAVAILABLE"))?;
    if url == song.youtube_url && title == song.title && tags == tags_json {
        Ok(false)
    } else {
        Err(Error::new(409, "IDEMPOTENCY_CONFLICT"))
    }
}

fn db_error(_: rusqlite::Error) -> Error {
    Error::new(500, "STORAGE_UNAVAILABLE")
}

#[cfg(test)]
#[path = "catalogue_tests.rs"]
mod tests;
