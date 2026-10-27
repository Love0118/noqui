//! Installed, immutable playlist audio. It never enters the disposable cache database.
use crate::{catalogue::SongRecord, jobs::Media};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    fs,
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
};
#[cfg(test)]
#[path = "library_tests.rs"]
mod tests;

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Audio {
    pub path: String,
    pub sha256: String,
    pub byte_length: u64,
    pub duration_ms: i64,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Track {
    pub song: SongRecord,
    pub audio: Option<Audio>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Playlist {
    pub schema_version: u32,
    pub id: String,
    pub name: String,
    pub songs: Vec<Track>,
}
#[derive(Default)]
pub struct Library {
    playlists: Vec<Playlist>,
    assets: HashMap<String, (Media, PathBuf)>,
}
fn slug(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
}
fn media_id(audio: &Audio) -> String {
    // Content-derived UUIDs avoid collisions when catalogues share a song ID but different recordings.
    let mut bytes = [0u8; 16];
    for (i, byte) in bytes.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&audio.sha256[i * 2..i * 2 + 2], 16).unwrap();
    }
    bytes[6] = (bytes[6] & 0x0f) | 0x80;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    uuid::Uuid::from_bytes(bytes).to_string()
}
fn regular(path: &Path) -> Result<(), String> {
    if !fs::symlink_metadata(path).is_ok_and(|m| m.is_file() && !m.file_type().is_symlink()) {
        return Err("Invalid library file".into());
    }
    Ok(())
}
fn directory(path: &Path) -> Result<(), String> {
    if !fs::symlink_metadata(path).is_ok_and(|m| m.is_dir() && !m.file_type().is_symlink()) {
        return Err("Invalid library directory".into());
    }
    Ok(())
}
fn load(manifest: &Path) -> Result<Playlist, String> {
    regular(manifest)?;
    if fs::metadata(manifest).map_err(|e| e.to_string())?.len() > 16 * 1024 * 1024 {
        return Err("Playlist manifest too large".into());
    }
    let playlist: Playlist =
        serde_json::from_slice(&fs::read(manifest).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())?;
    if playlist.schema_version != 1
        || !slug(&playlist.id)
        || playlist.name.trim() != playlist.name
        || playlist.name.is_empty()
        || playlist.name.chars().count() > 64
        || playlist.name.chars().any(char::is_control)
        || playlist.songs.is_empty()
        || playlist.songs.len() > 10000
    {
        return Err("Invalid playlist manifest".into());
    }
    let mut ids = HashSet::new();
    for track in &playlist.songs {
        track.song.validate().map_err(|e| e.code.to_string())?;
        if !ids.insert(&track.song.id) {
            return Err("Duplicate playlist track".into());
        }
        if let Some(audio) = &track.audio {
            let video = track.song.youtube_url.rsplit('=').next().unwrap_or("");
            if audio.path != format!("audio/{video}.ogg")
                || audio.sha256.len() != 64
                || !audio
                    .sha256
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                || audio.duration_ms <= 0
                || audio.byte_length == 0
                || audio.byte_length > 128 * 1024 * 1024
            {
                return Err("Invalid playlist audio".into());
            }
            let parent = manifest.parent().ok_or("Invalid manifest path")?;
            directory(&parent.join("audio"))?;
            let path = parent.join(&audio.path);
            regular(&path)?;
            if fs::metadata(&path).map_err(|e| e.to_string())?.len() != audio.byte_length {
                return Err("Library size mismatch".into());
            }
            let mut file = fs::File::open(&path).map_err(|e| e.to_string())?;
            let mut hash = Sha256::new();
            let mut buffer = [0u8; 65536];
            loop {
                let n = file.read(&mut buffer).map_err(|e| e.to_string())?;
                if n == 0 {
                    break;
                }
                hash.update(&buffer[..n]);
            }
            if format!("{:x}", hash.finalize()) != audio.sha256 {
                return Err("Library hash mismatch".into());
            }
            let duration = opus_duration(&path)?;
            if (duration - audio.duration_ms).abs() > 20 {
                return Err("Library duration mismatch".into());
            }
        }
    }
    Ok(playlist)
}
fn opus_duration(path: &Path) -> Result<i64, String> {
    let mut file = fs::File::open(path).map_err(|e| e.to_string())?;
    let mut granule = 0u64;
    let mut pre_skip = 0u16;
    let mut serial = None;
    let mut first = true;
    let mut eos = false;
    loop {
        let mut header = [0u8; 27];
        let n = file.read(&mut header[..1]).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        file.read_exact(&mut header[1..])
            .map_err(|_| "Truncated Ogg")?;
        if &header[..5] != b"OggS\0" || eos {
            return Err("Invalid Ogg stream".into());
        }
        let stream = u32::from_le_bytes(header[14..18].try_into().unwrap());
        if serial.is_some_and(|s| s != stream) {
            return Err("Chained Ogg stream".into());
        }
        serial = Some(stream);
        let g = u64::from_le_bytes(header[6..14].try_into().unwrap());
        if g != u64::MAX {
            granule = g;
        }
        let mut lacing = vec![0u8; header[26] as usize];
        file.read_exact(&mut lacing).map_err(|_| "Truncated Ogg")?;
        let size: usize = lacing.iter().map(|n| *n as usize).sum();
        if first {
            let mut body = vec![0u8; size];
            file.read_exact(&mut body).map_err(|_| "Truncated Opus")?;
            if body.len() < 19 || &body[..8] != b"OpusHead" || body[8] != 1 || body[9] != 2 {
                return Err("Expected stereo Opus".into());
            }
            pre_skip = u16::from_le_bytes(body[10..12].try_into().unwrap());
            first = false;
        } else {
            let end = file
                .seek(SeekFrom::Current(size as i64))
                .map_err(|e| e.to_string())?;
            if end > file.metadata().map_err(|e| e.to_string())?.len() {
                return Err("Truncated Ogg body".into());
            }
        }
        eos = header[5] & 4 != 0;
    }
    if first || !eos || granule <= pre_skip as u64 {
        return Err("Incomplete Opus stream".into());
    }
    Ok(((granule - pre_skip as u64) * 1000 / 48000) as i64)
}
impl Library {
    pub fn open(root: &Path) -> Result<Self, String> {
        fs::create_dir_all(root).map_err(|e| e.to_string())?;
        directory(root)?;
        let mut library = Self::default();
        for entry in fs::read_dir(root).map_err(|e| e.to_string())? {
            let entry = entry.map_err(|e| e.to_string())?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if !slug(&name) {
                continue;
            }
            directory(&entry.path())?;
            let playlist = load(&entry.path().join("playlist.json"))?;
            if playlist.id != name {
                return Err("Playlist folder mismatch".into());
            }
            for track in &playlist.songs {
                if let Some(a) = &track.audio {
                    let media = Media {
                        id: media_id(a),
                        sha256: a.sha256.clone(),
                        duration_ms: a.duration_ms,
                        byte_length: a.byte_length,
                    };
                    let path = entry.path().join(&a.path);
                    if let Some((old, _)) = library.assets.get(&media.id)
                        && old.sha256 != media.sha256
                    {
                        return Err("Conflicting library media ID".into());
                    }
                    library.assets.insert(media.id.clone(), (media, path));
                }
            }
            library.playlists.push(playlist);
        }
        library.playlists.sort_by(|a, b| a.id.cmp(&b.id));
        Ok(library)
    }
    pub fn summaries(&self) -> serde_json::Value {
        serde_json::json!({"playlists":self.playlists.iter().map(|p|serde_json::json!({"id":p.id,"name":p.name,"trackCount":p.songs.len(),"playableCount":p.songs.iter().filter(|t|t.audio.is_some()).count()})).collect::<Vec<_>>()})
    }
    pub fn tracks(&self, id: &str, offset: usize) -> Option<serde_json::Value> {
        let p = self.playlists.iter().find(|p| p.id == id)?;
        let ready: Vec<_> = p.songs.iter().filter(|t| t.audio.is_some()).collect();
        let mut tracks = Vec::new();
        let mut bytes = 256;
        for track in ready.iter().skip(offset).take(50) {
            let value = serde_json::json!({"song":track.song,"mediaId":media_id(track.audio.as_ref().unwrap()),"durationMs":track.audio.as_ref().unwrap().duration_ms});
            let size = serde_json::to_vec(&value).ok()?.len();
            if bytes + size > 60000 {
                break;
            }
            bytes += size;
            tracks.push(value);
        }
        let next = offset.saturating_add(tracks.len());
        Some(
            serde_json::json!({"id":p.id,"tracks":tracks,"nextOffset":if next<ready.len(){Some(next)}else{None}}),
        )
    }
    pub fn asset(&self, id: &str) -> Option<&(Media, PathBuf)> {
        self.assets.get(id)
    }
}
/// Validate the entire bundle before atomically exposing it. Existing installations stay intact.
pub fn install(data: &Path, manifest: &Path) -> Result<(), String> {
    let playlist = load(manifest)?;
    let library = data.join("library");
    fs::create_dir_all(&library).map_err(|e| e.to_string())?;
    directory(&library)?;
    let target = library.join(&playlist.id);
    if target.exists() {
        return Err("Playlist already installed; archive its folder before replacing it".into());
    }
    let staging = library.join(format!(".install-{}", uuid::Uuid::new_v4()));
    fs::create_dir(&staging).map_err(|e| e.to_string())?;
    let result = (|| {
        fs::create_dir(staging.join("audio")).map_err(|e| e.to_string())?;
        for track in &playlist.songs {
            if let Some(audio) = &track.audio {
                fs::copy(
                    manifest.parent().unwrap().join(&audio.path),
                    staging.join(&audio.path),
                )
                .map_err(|e| e.to_string())?;
            }
        }
        fs::write(
            staging.join("playlist.json"),
            serde_json::to_vec_pretty(&playlist).map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;
        load(&staging.join("playlist.json"))?;
        fs::rename(&staging, &target).map_err(|e| e.to_string())?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staging);
    }
    result
}
