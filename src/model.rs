use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use uuid::Uuid;

#[derive(Clone, Debug)]
pub struct Error {
    pub status: u16,
    pub code: &'static str,
    pub retryable: bool,
    pub retry_after: u64,
}
pub type Result<T> = std::result::Result<T, Error>;
impl Error {
    pub fn new(status: u16, code: &'static str) -> Self {
        Self {
            status,
            code,
            retryable: false,
            retry_after: 0,
        }
    }
    pub fn retry(status: u16, code: &'static str, retry_after: u64) -> Self {
        Self {
            status,
            code,
            retryable: true,
            retry_after,
        }
    }
    pub fn body(&self) -> Value {
        json!({"error":{"code":self.code,"message":self.code,"retryable":self.retryable,"retryAfterMs":self.retry_after,"requestId":Uuid::new_v4()}})
    }
}
pub fn bad() -> Error {
    Error::new(400, "INVALID_REQUEST")
}
pub fn uuid(s: &str) -> bool {
    Uuid::parse_str(s).is_ok_and(|v| v.to_string() == s)
}
pub fn hex(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
pub fn video(s: &str) -> bool {
    s.len() == 11
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}
pub fn counter(s: &str) -> Result<u64> {
    if s.is_empty() || (s.len() > 1 && s.starts_with('0')) || !s.bytes().all(|b| b.is_ascii_digit())
    {
        return Err(bad());
    }
    s.parse().map_err(|_| bad())
}

#[derive(Clone, Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Player {
    pub uuid: String,
    pub membership_id: String,
    pub nickname: String,
    pub texture_hash: Option<String>,
    pub room_id: String,
}
#[derive(Clone, Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Round {
    pub round_id: String,
    pub number: u32,
    pub revision: String,
    pub playback_generation: String,
    pub media_id: Option<String>,
    pub clip_start_ms: i64,
    pub clip_end_ms: i64,
    pub anchor_position_ms: i64,
    pub anchor_at_ms: i64,
    pub playing: bool,
}
#[derive(Clone, Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Reveal {
    pub title: String,
    pub aliases: Vec<String>,
    pub tags: Vec<String>,
    pub youtube_url: String,
}
#[derive(Clone, Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Room {
    pub room_id: String,
    pub room_revision: String,
    pub phase: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub round_number: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub total_rounds: Option<u32>,
    pub round: Option<Round>,
    pub reveal: Option<Reveal>,
}
#[derive(Clone, Serialize, Deserialize, Debug)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Snapshot {
    pub schema_version: u32,
    pub instance_id: String,
    pub seq: String,
    pub sent_at_ms: i64,
    pub roster: Vec<Player>,
    pub rooms: Vec<Room>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct JobRequest {
    pub schema_version: u32,
    pub request_id: String,
    pub video_id: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TokenRequest {
    pub schema_version: u32,
    pub request_id: String,
    pub instance_id: String,
    pub uuid: String,
    pub membership_id: String,
    pub room_id: String,
    pub token_sha256: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Redeem {
    pub token: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Playback {
    pub client_id: String,
    pub take_over: bool,
    pub round_id: Option<String>,
    pub round_revision: Option<String>,
    pub playback_generation: Option<String>,
    pub status: String,
    pub position_ms: Option<i64>,
    pub buffered_until_ms: Option<i64>,
    pub last_event_id: Option<String>,
}
