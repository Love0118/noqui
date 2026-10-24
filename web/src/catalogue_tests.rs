use super::*;
use serde_json::json;

const ID: &str = "c7591db4-53dd-42b9-805a-c78538d1094a";
const SECOND_ID: &str = "5c7e5b06-8e3d-4eb5-a6db-a2163d22aa95";
const VIDEO: &str = "BaW_jenozKc";

fn metadata() -> SongMetadata {
    SongMetadata {
        title: "노래 제목 A".into(),
        tags: vec!["가수".into(), "별명 2".into()],
    }
}

fn record() -> SongRecord {
    SongRecord::from_job(ID, VIDEO, &metadata()).unwrap()
}

fn database() -> Connection {
    let db = Connection::open_in_memory().unwrap();
    initialize(&db).unwrap();
    db
}

fn failure<T: std::fmt::Debug>(result: Result<T>, status: u16, code: &str) {
    let error = result.unwrap_err();
    assert_eq!(error.status, status);
    assert_eq!(error.code, code);
}

fn stored(db: &Connection, id: &str) -> (SongRecord, i64, i64) {
    db.query_row(
        "SELECT id, youtube_url, title, tags_json, created, updated
         FROM song_catalogue WHERE id = ?1",
        [id],
        |row| {
            let tags: String = row.get(3)?;
            Ok((
                SongRecord {
                    id: row.get(0)?,
                    youtube_url: row.get(1)?,
                    title: row.get(2)?,
                    tags: serde_json::from_str(&tags).unwrap(),
                },
                row.get(4)?,
                row.get(5)?,
            ))
        },
    )
    .unwrap()
}

fn count(db: &Connection) -> i64 {
    db.query_row("SELECT COUNT(*) FROM song_catalogue", [], |row| row.get(0))
        .unwrap()
}

#[test]
fn job_record_preserves_submission_id_metadata_and_canonical_url() {
    let song = record();
    assert_eq!(song.id, ID);
    assert_eq!(
        song.youtube_url,
        "https://www.youtube.com/watch?v=BaW_jenozKc"
    );
    assert_eq!(song.title, metadata().title);
    assert_eq!(song.tags, metadata().tags);
    song.validate().unwrap();
}

#[test]
fn dto_wire_names_are_camel_case_and_unknown_fields_are_rejected() {
    let song = record();
    let encoded = serde_json::to_value(&song).unwrap();
    assert!(encoded.get("youtubeUrl").is_some());
    assert!(encoded.get("youtube_url").is_none());
    assert_eq!(
        serde_json::from_value::<SongRecord>(encoded.clone()).unwrap(),
        song
    );
    let mut extra = encoded;
    extra["mediaId"] = json!("private-media-id");
    assert!(serde_json::from_value::<SongRecord>(extra).is_err());
    assert!(
        serde_json::from_value::<SongMetadata>(json!({
            "title":"Song", "tags":[], "youtubeUrl":"unexpected"
        }))
        .is_err()
    );
    assert!(serde_json::from_value::<SongMetadata>(json!({"title":"Song","tags":null})).is_err());
    assert!(serde_json::from_value::<SongMetadata>(json!({"title":"Song"})).is_err());
    assert!(
        serde_json::from_value::<CatalogueImportRequest>(json!({
            "schemaVersion":1,"songs":[],"replace":true
        }))
        .is_err()
    );
    let request = CatalogueImportRequest {
        schema_version: 1,
        songs: vec![song],
    };
    assert_eq!(serde_json::to_value(&request).unwrap()["schemaVersion"], 1);
}

#[test]
fn unicode_scalar_limits_do_not_count_utf8_bytes_or_utf16_surrogates() {
    for title in ["가".repeat(160), format!("A{}", "🎵".repeat(159))] {
        let value = SongMetadata {
            title,
            tags: vec![format!("A{}", "🎵".repeat(31)); 10],
        };
        value.validate().unwrap();
    }
    for value in [
        SongMetadata {
            title: "가".repeat(161),
            tags: vec![],
        },
        SongMetadata {
            title: format!("A{}", "🎵".repeat(160)),
            tags: vec![],
        },
        SongMetadata {
            title: "A".into(),
            tags: vec![format!("A{}", "🎵".repeat(32))],
        },
        SongMetadata {
            title: "A".into(),
            tags: vec!["tag".into(); 11],
        },
    ] {
        failure(value.validate(), 422, "INVALID_SONG_METADATA");
    }
    SongMetadata {
        title: "A".into(),
        tags: vec![],
    }
    .validate()
    .unwrap();
}

#[test]
fn text_requires_trimmed_noncontrol_text_with_java_answer_characters() {
    for text in [
        "",
        " ",
        " A",
        "A ",
        "A\nB",
        "A\tB",
        "A\u{0000}B",
        "A\u{007f}B",
        "A\u{0085}B",
        "A\u{009f}B",
        "🎵",
        "!?",
        "é",
        "漢字",
        "ㄱㄴ",
        "\u{abff}",
        "\u{d7a4}",
    ] {
        failure(
            SongMetadata {
                title: text.into(),
                tags: vec![],
            }
            .validate(),
            422,
            "INVALID_SONG_METADATA",
        );
        failure(
            SongMetadata {
                title: "Song".into(),
                tags: vec![text.into()],
            }
            .validate(),
            422,
            "INVALID_SONG_METADATA",
        );
    }
    // Java String.trim() preserves non-ASCII spaces when meaningful text is present.
    for text in [
        "0",
        "A",
        "z",
        "가",
        "힣",
        "A 🎵",
        "한 글",
        "A-é",
        "\u{2003}A",
        "A\u{2003}",
        "\u{00a0}A\u{00a0}",
    ] {
        SongMetadata {
            title: text.into(),
            tags: vec![text.into()],
        }
        .validate()
        .unwrap();
    }
}

#[test]
fn job_sources_require_canonical_uuid_and_eleven_safe_video_characters() {
    for id in [
        "",
        "not-a-uuid",
        "C7591DB4-53DD-42B9-805A-C78538D1094A",
        "c7591db453dd42b9805ac78538d1094a",
    ] {
        failure(
            SongRecord::from_job(id, VIDEO, &metadata()),
            422,
            "INVALID_SOURCE",
        );
    }
    for video in [
        "",
        "abcdefghij",
        "abcdefghijkl",
        "abcdefghij/",
        "abcdefghij?",
        "가abcdefghij",
    ] {
        failure(
            SongRecord::from_job(ID, video, &metadata()),
            422,
            "INVALID_SOURCE",
        );
    }
    SongRecord::from_job(ID, "_a-A0123456", &metadata()).unwrap();
}

#[test]
fn imports_require_the_exact_canonical_url_without_extra_parameters() {
    for url in [
        "http://www.youtube.com/watch?v=BaW_jenozKc",
        "https://youtube.com/watch?v=BaW_jenozKc",
        "https://youtu.be/BaW_jenozKc",
        "https://www.youtube.com/watch?v=BaW_jenozKc&t=1",
        "https://www.youtube.com/watch?v=BaW_jenozKc#fragment",
        "https://www.youtube.com/watch?v=BaW%5FjenozKc",
        "https://www.youtube.com/watch?v=BaW_jenozKc ",
        "https://www.youtube.com.evil.invalid/watch?v=BaW_jenozKc",
    ] {
        let mut song = record();
        song.youtube_url = url.into();
        failure(song.validate(), 422, "INVALID_SOURCE");
    }
}

#[test]
fn import_batch_validates_schema_limit_and_every_record_before_writes() {
    CatalogueImportRequest {
        schema_version: 1,
        songs: vec![],
    }
    .validate()
    .unwrap();
    CatalogueImportRequest {
        schema_version: 1,
        songs: vec![record(); MAX_IMPORT_SONGS],
    }
    .validate()
    .unwrap();
    for request in [
        CatalogueImportRequest {
            schema_version: 2,
            songs: vec![],
        },
        CatalogueImportRequest {
            schema_version: 1,
            songs: vec![record(); MAX_IMPORT_SONGS + 1],
        },
    ] {
        failure(request.validate(), 400, "INVALID_REQUEST");
    }
    let mut invalid = record();
    invalid.tags.push("".into());
    failure(
        CatalogueImportRequest {
            schema_version: 1,
            songs: vec![record(), invalid],
        }
        .validate(),
        422,
        "INVALID_SONG_METADATA",
    );
}

#[test]
fn save_is_exactly_idempotent_and_preserves_original_timestamps() {
    let db = database();
    let song = record();
    assert!(save(&db, &song, 100).unwrap());
    assert!(!save(&db, &song, 200).unwrap());
    initialize(&db).unwrap();
    assert_eq!(count(&db), 1);
    assert_eq!(stored(&db, ID), (song, 100, 100));
}

#[test]
fn conflicting_id_never_overwrites_url_title_or_ordered_tags() {
    let db = database();
    let original = record();
    save(&db, &original, 100).unwrap();
    let mut other_url = original.clone();
    other_url.youtube_url = format!("{YOUTUBE_PREFIX}abcdefghijk");
    let mut other_title = original.clone();
    other_title.title.push('!');
    let mut other_tags = original.clone();
    other_tags.tags.push("추가 태그".into());
    let mut reordered = original.clone();
    reordered.tags.reverse();
    for changed in [other_url, other_title, other_tags, reordered] {
        failure(save(&db, &changed, 300), 409, "IDEMPOTENCY_CONFLICT");
        assert_eq!(stored(&db, ID), (original.clone(), 100, 100));
        assert_eq!(count(&db), 1);
    }
}

#[test]
fn repeated_video_submissions_keep_separate_stable_song_ids() {
    let db = database();
    let first = record();
    let second = SongRecord::from_job(SECOND_ID, VIDEO, &metadata()).unwrap();
    assert!(save(&db, &first, 1).unwrap());
    assert!(save(&db, &second, 2).unwrap());
    assert_eq!(count(&db), 2);
    assert_eq!(stored(&db, ID).0.id, ID);
    assert_eq!(stored(&db, SECOND_ID).0.id, SECOND_ID);
}

#[test]
fn sql_parameters_and_tags_json_preserve_private_punctuation_and_unicode() {
    let db = database();
    let metadata = SongMetadata {
        title: "노래 '); DROP TABLE song_catalogue; --".into(),
        tags: vec!["A \"quote\"".into(), "가\\수 🎵".into()],
    };
    let song = SongRecord::from_job(ID, VIDEO, &metadata).unwrap();
    save(&db, &song, 7).unwrap();
    assert_eq!(stored(&db, ID).0, song);
    assert_eq!(count(&db), 1);
}

#[test]
fn catalogue_survives_reopen_and_cache_row_or_table_eviction() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("media.sqlite");
    {
        let db = Connection::open(&path).unwrap();
        db.execute_batch("PRAGMA foreign_keys=ON; CREATE TABLE media(id TEXT PRIMARY KEY); CREATE TABLE jobs(id TEXT PRIMARY KEY); CREATE TABLE requests(id TEXT PRIMARY KEY);").unwrap();
        initialize(&db).unwrap();
        save(&db, &record(), 90).unwrap();
        let foreign_keys: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM pragma_foreign_key_list('song_catalogue')",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(foreign_keys, 0);
        db.execute_batch("DELETE FROM requests; DELETE FROM jobs; DELETE FROM media; DROP TABLE requests; DROP TABLE jobs; DROP TABLE media; VACUUM;").unwrap();
    }
    let reopened = Connection::open(path).unwrap();
    initialize(&reopened).unwrap();
    assert_eq!(stored(&reopened, ID), (record(), 90, 90));
    assert!(!save(&reopened, &record(), 500).unwrap());
}

#[test]
fn caller_can_roll_back_catalogue_and_job_acceptance_together() {
    let mut db = database();
    db.execute("CREATE TABLE accepted_jobs(id TEXT PRIMARY KEY)", [])
        .unwrap();
    {
        let tx = db.transaction().unwrap();
        tx.execute("INSERT INTO accepted_jobs(id) VALUES (?1)", [ID])
            .unwrap();
        assert!(save(&tx, &record(), 20).unwrap());
        assert!(!tx.is_autocommit());
        // Dropping the caller transaction must roll back both changes.
    }
    assert_eq!(count(&db), 0);
    let jobs: i64 = db
        .query_row("SELECT COUNT(*) FROM accepted_jobs", [], |row| row.get(0))
        .unwrap();
    assert_eq!(jobs, 0);
    let tx = db.transaction().unwrap();
    tx.execute("INSERT INTO accepted_jobs(id) VALUES (?1)", [ID])
        .unwrap();
    save(&tx, &record(), 30).unwrap();
    tx.commit().unwrap();
    assert_eq!(stored(&db, ID).1, 30);
}

#[test]
fn import_conflict_can_roll_back_earlier_records_without_overwriting_existing_data() {
    let mut db = database();
    save(&db, &record(), 10).unwrap();
    {
        let tx = db.transaction().unwrap();
        let new_song = SongRecord::from_job(SECOND_ID, VIDEO, &metadata()).unwrap();
        save(&tx, &new_song, 20).unwrap();
        let mut conflict = record();
        conflict.title = "다른 제목".into();
        failure(save(&tx, &conflict, 20), 409, "IDEMPOTENCY_CONFLICT");
        assert!(!tx.is_autocommit());
    }
    assert_eq!(count(&db), 1);
    assert_eq!(stored(&db, ID), (record(), 10, 10));
}

#[test]
fn save_revalidates_directly_constructed_records_and_has_no_partial_writes() {
    let db = database();
    let mut song = record();
    song.title = " ".into();
    failure(save(&db, &song, 1), 422, "INVALID_SONG_METADATA");
    song = record();
    song.id = "not-a-uuid".into();
    failure(save(&db, &song, 1), 422, "INVALID_SOURCE");
    assert_eq!(count(&db), 0);
}

#[test]
fn storage_failures_are_sanitized_and_never_include_song_metadata() {
    let missing = Connection::open_in_memory().unwrap();
    let error = save(&missing, &record(), 1).unwrap_err();
    assert_eq!(error.status, 500);
    assert_eq!(error.code, "STORAGE_UNAVAILABLE");
    assert!(!error.body().to_string().contains(&record().title));
    let read_only = database();
    read_only.execute_batch("PRAGMA query_only=ON").unwrap();
    failure(save(&read_only, &record(), 1), 500, "STORAGE_UNAVAILABLE");
    assert_eq!(count(&read_only), 0);
    let no_schema = Connection::open_in_memory().unwrap();
    no_schema.execute_batch("PRAGMA query_only=ON").unwrap();
    failure(initialize(&no_schema), 500, "STORAGE_UNAVAILABLE");
}
