use super::*;
use crate::{auth, jobs::Jobs};

fn page(body: &[u8], flags: u8, granule: u64) -> Vec<u8> {
    let mut header = vec![0u8; 27];
    header[..5].copy_from_slice(b"OggS\0");
    header[5] = flags;
    header[6..14].copy_from_slice(&granule.to_le_bytes());
    header[26] = 1;
    header.push(body.len() as u8);
    header.extend_from_slice(body);
    header
}
fn bundle(root: &Path) -> (PathBuf, String) {
    fs::create_dir_all(root.join("audio")).unwrap();
    let mut head = b"OpusHead\x01\x02".to_vec();
    head.extend_from_slice(&[0; 9]);
    let mut bytes = page(&head, 2, 0);
    bytes.extend(page(b"OpusTags\0\0\0\0\0\0\0\0", 0, 0));
    bytes.extend(page(b"audio", 4, 48000));
    fs::write(root.join("audio/AAAAAAAAAAA.ogg"), &bytes).unwrap();
    let id = uuid::Uuid::new_v4().to_string();
    let manifest = root.join("playlist.json");
    let playlist = Playlist {
        excluded_songs: vec![],
        schema_version: 1,
        id: "test-playlist".into(),
        name: "씹노퀴".into(),
        songs: vec![Track {
            song: SongRecord {
                id: id.clone(),
                youtube_url: "https://www.youtube.com/watch?v=AAAAAAAAAAA".into(),
                title: "Private answer".into(),
                tags: vec!["writer".into()],
            },
            audio: Some(Audio {
                path: "audio/AAAAAAAAAAA.ogg".into(),
                sha256: auth::digest(&bytes),
                byte_length: bytes.len() as u64,
                duration_ms: 1000,
            }),
        }],
    };
    fs::write(&manifest, serde_json::to_vec(&playlist).unwrap()).unwrap();
    (manifest, id)
}
#[test]
fn install_is_validated_atomic_and_refuses_path_escape_hash_or_duration_mismatch() {
    let dir = tempfile::tempdir().unwrap();
    let (manifest, _) = bundle(&dir.path().join("source"));
    let data = dir.path().join("data");
    let mut p: Playlist = serde_json::from_slice(&fs::read(&manifest).unwrap()).unwrap();
    for field in 0..3 {
        let mut invalid = p.clone();
        let audio = invalid.songs[0].audio.as_mut().unwrap();
        match field {
            0 => audio.path = "../outside.ogg".into(),
            1 => audio.sha256 = "0".repeat(64),
            _ => audio.duration_ms = 2000,
        }
        fs::write(&manifest, serde_json::to_vec(&invalid).unwrap()).unwrap();
        assert!(install(&data, &manifest).is_err());
        assert!(!data.join("library/test-playlist").exists());
    }
    fs::write(&manifest, serde_json::to_vec(&p).unwrap()).unwrap();
    install(&data, &manifest).unwrap();
    assert!(install(&data, &manifest).is_err());
    let library = Library::open(&data.join("library")).unwrap();
    assert_eq!(library.summaries()["playlists"][0]["playableCount"], 1);
    p.songs[0].audio = None;
    p.songs[0].song.id = uuid::Uuid::new_v4().to_string();
    p.id = "missing".into();
    fs::write(&manifest, serde_json::to_vec(&p).unwrap()).unwrap();
    install(&data, &manifest).unwrap();
    let library = Library::open(&data.join("library")).unwrap();
    assert_eq!(
        library.tracks("missing", 0).unwrap()["tracks"]
            .as_array()
            .unwrap()
            .len(),
        0
    );
}
#[tokio::test]
async fn library_survives_cache_eviction_and_restart_without_cache_accounting() {
    let dir = tempfile::tempdir().unwrap();
    let (manifest, _) = bundle(&dir.path().join("source"));
    let data = dir.path().join("data");
    install(&data, &manifest).unwrap();
    let jobs = Jobs::open(&data, 1000).unwrap();
    let id = jobs.library.tracks("test-playlist", 0).unwrap()["tracks"][0]["mediaId"]
        .as_str()
        .unwrap()
        .to_owned();
    let media = jobs
        .media(vec![id.clone()])
        .await
        .unwrap()
        .remove(&id)
        .unwrap();
    assert_eq!(media.duration_ms, 1000);
    let (mut file, _guard) = jobs.open_media(media, 1000, true).await.unwrap();
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes).unwrap();
    assert!(!bytes.is_empty());
    let db = rusqlite::Connection::open(data.join("media.sqlite")).unwrap();
    let count: i64 = db
        .query_row("SELECT COUNT(*) FROM media", [], |r| r.get(0))
        .unwrap();
    assert_eq!(count, 0);
    drop(jobs);
    let restarted = Jobs::open(&data, 2000).unwrap();
    assert_eq!(restarted.media(vec![id]).await.unwrap().len(), 1);
}
#[test]
fn private_track_pages_are_bounded_and_exclude_unavailable_entries() {
    let dir = tempfile::tempdir().unwrap();
    let (manifest, _) = bundle(dir.path());
    let mut p: Playlist = serde_json::from_slice(&fs::read(manifest).unwrap()).unwrap();
    let template = p.songs[0].clone();
    p.songs = (0..121)
        .map(|_| {
            let mut t = template.clone();
            t.song.id = uuid::Uuid::new_v4().to_string();
            t
        })
        .collect();
    p.songs[120].audio = None;
    let library = Library {
        playlists: vec![p],
        assets: HashMap::new(),
    };
    let first = library.tracks("test-playlist", 0).unwrap();
    assert_eq!(first["tracks"].as_array().unwrap().len(), 50);
    assert_eq!(first["nextOffset"], 50);
    let last = library.tracks("test-playlist", 100).unwrap();
    assert_eq!(last["tracks"].as_array().unwrap().len(), 20);
    assert!(last["nextOffset"].is_null());
    assert!(
        serde_json::to_string(&library.summaries())
            .unwrap()
            .find("Private answer")
            .is_none()
    );
}
#[test]
fn unicode_heavy_pages_stay_within_bridge_response_limit() {
    let dir = tempfile::tempdir().unwrap();
    let (manifest, _) = bundle(dir.path());
    let mut p: Playlist = serde_json::from_slice(&fs::read(manifest).unwrap()).unwrap();
    p.songs[0].song.title = "가".repeat(160);
    p.songs[0].song.tags = (0..10)
        .map(|i| format!("{}{}", "나".repeat(31), i))
        .collect();
    p.songs = vec![p.songs[0].clone(); 100];
    let library = Library {
        playlists: vec![p],
        assets: HashMap::new(),
    };
    let page = library.tracks("test-playlist", 0).unwrap();
    assert!(serde_json::to_vec(&page).unwrap().len() < 65536);
    assert!(page["tracks"].as_array().unwrap().len() < 50);
    assert_eq!(
        page["nextOffset"].as_u64().unwrap() as usize,
        page["tracks"].as_array().unwrap().len()
    );
}
#[test]
fn unreviewed_chart_entries_remain_counted_without_entering_playback_pages() {
    let dir = tempfile::tempdir().unwrap();
    let (manifest, _) = bundle(dir.path());
    let mut p: Playlist = serde_json::from_slice(&fs::read(manifest).unwrap()).unwrap();
    p.excluded_songs = vec![ExcludedSong {
        index: 2,
        title: "未確認".into(),
        reasons: vec!["Original recording unresolved".into()],
    }];
    let library = Library {
        playlists: vec![p],
        assets: HashMap::new(),
    };
    assert_eq!(library.summaries()["playlists"][0]["trackCount"], 2);
    assert_eq!(library.summaries()["playlists"][0]["playableCount"], 1);
    assert_eq!(
        library.tracks("test-playlist", 0).unwrap()["tracks"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
}
