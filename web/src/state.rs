use crate::{auth, jobs::Media, model::*};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};

#[derive(Clone)]
pub struct Session {
    pub player: Player,
    pub csrf: String,
    pub absolute_until: i64,
    pub idle_until: i64,
    pub browser: Option<Browser>,
}
#[derive(Clone)]
pub struct Browser {
    pub client: String,
    pub until: i64,
    pub seen: i64,
    pub round: Option<String>,
    pub revision: Option<String>,
    pub generation: Option<String>,
    pub status: String,
    pub position: Option<i64>,
}
#[derive(Clone)]
struct Token {
    player: Player,
    instance: String,
    until: i64,
}
struct Receipt {
    fingerprint: String,
    until: i64,
}
pub struct State {
    pub epoch: String,
    pub event_seq: u64,
    pub nonces: HashMap<String, i64>,
    pub sessions: HashMap<String, Session>,
    tokens: HashMap<String, Token>,
    receipts: HashMap<String, Receipt>,
    pub snapshot: Option<Snapshot>,
    pub authority_until: i64,
    last_body: String,
    retired: HashSet<String>,
    pub media: HashMap<String, Media>,
    pub downloads: HashMap<String, usize>,
    pub rates: HashMap<String, (i64, u32)>,
    pub redeem_rate: (i64, u32),
}
impl Default for State {
    fn default() -> Self {
        Self {
            epoch: uuid::Uuid::new_v4().to_string(),
            event_seq: 0,
            nonces: HashMap::new(),
            sessions: HashMap::new(),
            tokens: HashMap::new(),
            receipts: HashMap::new(),
            snapshot: None,
            authority_until: 0,
            last_body: String::new(),
            retired: HashSet::new(),
            media: HashMap::new(),
            downloads: HashMap::new(),
            rates: HashMap::new(),
            redeem_rate: (0, 0),
        }
    }
}
impl State {
    pub fn expire(&mut self, now: i64) {
        if self.authority_until <= now {
            self.sessions.clear();
            self.tokens.clear();
        }
        self.tokens.retain(|_, t| t.until > now);
        self.receipts.retain(|_, r| r.until + 120000 > now);
        self.sessions
            .retain(|_, s| s.absolute_until > now && s.idle_until > now);
        self.rates.retain(|key, _| self.sessions.contains_key(key));
    }
    pub fn active_media(&self) -> HashSet<String> {
        self.snapshot
            .as_ref()
            .map(|s| {
                s.rooms
                    .iter()
                    .filter_map(|r| r.round.as_ref()?.media_id.clone())
                    .collect()
            })
            .unwrap_or_default()
    }
    pub fn session(&mut self, key: &str, now: i64) -> Result<Session> {
        self.expire(now);
        let session = self
            .sessions
            .get_mut(key)
            .ok_or(Error::new(401, "AUTH_REQUIRED"))?;
        session.idle_until = (now + 1800000).min(session.absolute_until);
        Ok(session.clone())
    }
    pub fn register(&mut self, r: TokenRequest, fingerprint: String, now: i64) -> Result<Value> {
        self.expire(now);
        if r.schema_version != 1 || !uuid(&r.request_id) || !hex(&r.token_sha256) {
            return Err(bad());
        }
        if let Some(receipt) = self.receipts.get(&r.request_id) {
            if receipt.fingerprint != fingerprint {
                return Err(Error::new(409, "IDEMPOTENCY_CONFLICT"));
            }
            return Ok(
                json!({"expiresAtMs":receipt.until,"expiresInSeconds":((receipt.until-now).max(0)+999)/1000}),
            );
        }
        let snapshot = self
            .snapshot
            .as_ref()
            .filter(|s| self.authority_until > now && s.instance_id == r.instance_id)
            .ok_or(Error::retry(503, "PLUGIN_OFFLINE", 2000))?;
        let player = snapshot
            .roster
            .iter()
            .find(|p| {
                p.uuid == r.uuid && p.membership_id == r.membership_id && p.room_id == r.room_id
            })
            .ok_or(Error::new(403, "MEMBERSHIP_REQUIRED"))?
            .clone();
        self.tokens.retain(|_, t| t.player.uuid != player.uuid);
        let until = now + 60000;
        self.tokens.insert(
            r.token_sha256,
            Token {
                player,
                instance: r.instance_id,
                until,
            },
        );
        self.receipts
            .insert(r.request_id, Receipt { fingerprint, until });
        Ok(json!({"expiresAtMs":until,"expiresInSeconds":60}))
    }
    pub fn redeem(&mut self, raw: &str, now: i64) -> Result<(String, Session)> {
        self.expire(now);
        if raw.len() != 43
            || !raw
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        {
            return Err(Error::new(401, "JOIN_INVALID"));
        }
        let hash = auth::digest(raw.as_bytes());
        let token = self
            .tokens
            .remove(&hash)
            .ok_or(Error::new(401, "JOIN_INVALID"))?;
        let current = self
            .snapshot
            .as_ref()
            .is_some_and(|s| s.instance_id == token.instance && s.roster.contains(&token.player));
        if !current || token.until <= now {
            return Err(Error::new(401, "JOIN_INVALID"));
        }
        self.sessions
            .retain(|_, s| s.player.uuid != token.player.uuid);
        let raw_session = auth::random();
        let session = Session {
            player: token.player,
            csrf: auth::random(),
            absolute_until: now + 28800000,
            idle_until: now + 1800000,
            browser: None,
        };
        self.sessions
            .insert(auth::digest(raw_session.as_bytes()), session.clone());
        self.event_seq += 1;
        Ok((raw_session, session))
    }
    pub fn sync(
        &mut self,
        s: Snapshot,
        body_hash: String,
        media: HashMap<String, Media>,
        now: i64,
    ) -> Result<Value> {
        self.expire(now);
        validate(&s, &media, now)?;
        if let Some(previous) = &self.snapshot {
            if s.instance_id != previous.instance_id {
                if self.authority_until > now {
                    return Err(Error::retry(
                        409,
                        "INSTANCE_ACTIVE",
                        (self.authority_until - now) as u64,
                    ));
                }
                if self.retired.contains(&s.instance_id) {
                    return Err(Error::new(409, "REVISION_CONFLICT"));
                }
                self.retired.insert(previous.instance_id.clone());
                if self.retired.len() > 128 {
                    self.retired.clear();
                    self.retired.insert(previous.instance_id.clone());
                }
            } else {
                let new_seq = counter(&s.seq)?;
                let old_seq = counter(&previous.seq)?;
                if new_seq < old_seq || (new_seq == old_seq && body_hash != self.last_body) {
                    return Err(Error::new(409, "REVISION_CONFLICT"));
                }
                if new_seq == old_seq {
                    return Ok(self.sync_response(now));
                }
                for room in &s.rooms {
                    if let Some(old) = previous.rooms.iter().find(|r| r.room_id == room.room_id) {
                        let next = counter(&room.room_revision)?;
                        let before = counter(&old.room_revision)?;
                        if next < before || (next == before && old != room) {
                            return Err(Error::new(409, "REVISION_CONFLICT"));
                        }
                        match (&room.round, &old.round) {
                            (Some(a), Some(b))
                                if a.round_id == b.round_id
                                    && (counter(&a.revision)? < counter(&b.revision)?
                                        || (a.revision == b.revision && a != b)) =>
                            {
                                return Err(Error::new(409, "REVISION_CONFLICT"));
                            }
                            _ => {}
                        }
                    }
                }
            }
        }
        self.sessions.retain(|_, session| {
            s.roster.iter().any(|p| {
                p.uuid == session.player.uuid
                    && p.membership_id == session.player.membership_id
                    && p.room_id == session.player.room_id
            })
        });
        for session in self.sessions.values_mut() {
            if let Some(p) = s.roster.iter().find(|p| p.uuid == session.player.uuid) {
                session.player = p.clone();
            }
        }
        self.tokens.retain(|_, t| {
            t.instance == s.instance_id
                && s.roster.iter().any(|p| {
                    p.uuid == t.player.uuid
                        && p.membership_id == t.player.membership_id
                        && p.room_id == t.player.room_id
                })
        });
        self.last_body = body_hash;
        self.media = media;
        self.snapshot = Some(s);
        self.authority_until = now + 15000;
        self.event_seq += 1;
        Ok(self.sync_response(now))
    }
    fn sync_response(&self, now: i64) -> Value {
        let browsers:Vec<Value>=self.snapshot.as_ref().map(|s|s.roster.iter().map(|p| {
            let b=self.sessions.values().find(|s|s.player.uuid==p.uuid).and_then(|s|s.browser.as_ref()).filter(|b|b.until>now);
            json!({"uuid":p.uuid,"membershipId":p.membership_id,"online":b.is_some(),"leaseExpiresAtMs":b.map(|x|x.until),"clientId":b.map(|x|&x.client),"roundId":b.and_then(|x|x.round.as_ref()),"roundRevision":b.and_then(|x|x.revision.as_ref()),"playbackGeneration":b.and_then(|x|x.generation.as_ref()),"status":b.map(|x|x.status.as_str()).unwrap_or("CONNECTED"),"positionMs":b.and_then(|x|x.position),"lastSeenAtMs":b.map(|x|x.seen)})
        }).collect()).unwrap_or_default();
        json!({"acceptedSeq":self.snapshot.as_ref().map(|s|&s.seq),"backendEpoch":self.epoch,"serverTimeMs":now,"authorityLeaseMs":15000,"browsers":browsers})
    }
    pub fn me(session: &Session) -> Value {
        json!({"me":{"uuid":session.player.uuid,"nickname":session.player.nickname,"textureHash":session.player.texture_hash,"roomId":session.player.room_id},"csrfToken":session.csrf,"sessionExpiresAtMs":session.absolute_until})
    }
    pub fn public(&mut self, key: &str, now: i64) -> Result<Value> {
        let session = self.session(key, now)?;
        let room = self
            .snapshot
            .as_ref()
            .and_then(|s| s.rooms.iter().find(|r| r.room_id == session.player.room_id))
            .ok_or(Error::new(404, "ROOM_NOT_FOUND"))?;
        let players:Vec<_>=self.snapshot.as_ref().unwrap().roster.iter().filter(|p|p.room_id==room.room_id).map(|p|json!({"uuid":p.uuid,"nickname":p.nickname,"textureHash":p.texture_hash,"browserOnline":self.sessions.values().any(|s|s.player.uuid==p.uuid && s.browser.as_ref().is_some_and(|b|b.until>now))})).collect();
        let media=room.round.as_ref().and_then(|r|r.media_id.as_ref()).and_then(|id|self.media.get(id)).map(|m|json!({"mediaId":m.id,"url":format!("/media/{}.ogg",m.sha256),"mime":"audio/ogg; codecs=opus","durationMs":m.duration_ms}));
        let mut public_room = json!({"roomId":room.room_id,"revision":room.room_revision,"phase":room.phase,"players":players});
        if let (Some(number), Some(total)) = (room.round_number, room.total_rounds) {
            public_room["roundNumber"] = json!(number);
            public_room["totalRounds"] = json!(total);
        }
        Ok(
            json!({"eventId":format!("{}:{}",self.epoch,self.event_seq),"serverTimeMs":now,"authorityLeaseExpiresAtMs":self.authority_until,"me":{"uuid":session.player.uuid,"nickname":session.player.nickname,"textureHash":session.player.texture_hash},"room":public_room,"round":room.round,"media":media,"reveal":if room.phase=="REVEAL" {room.reveal.as_ref()} else {None}}),
        )
    }
    pub fn playback(&mut self, key: &str, p: Playback, now: i64) -> Result<Value> {
        let session = self.session(key, now)?;
        if !uuid(&p.client_id)
            || ![
                "CONNECTED",
                "NEEDS_GESTURE",
                "LOADING",
                "PREPARED",
                "PLAYING",
                "PAUSED",
                "BUFFERING",
                "FAILED",
            ]
            .contains(&p.status.as_str())
            || p.position_ms.is_some_and(|v| !(0..=310000).contains(&v))
            || p.buffered_until_ms
                .is_some_and(|v| !(0..=310000).contains(&v))
            || p.last_event_id.as_ref().is_some_and(|v| v.len() > 100)
        {
            return Err(bad());
        }
        if session
            .browser
            .as_ref()
            .is_some_and(|b| b.client != p.client_id && b.until > now)
            && !p.take_over
        {
            return Err(Error::new(409, "ACTIVE_CLIENT"));
        }
        let current = self
            .snapshot
            .as_ref()
            .and_then(|s| s.rooms.first())
            .and_then(|r| r.round.as_ref());
        let matches = current.is_some_and(|r| {
            Some(&r.round_id) == p.round_id.as_ref()
                && Some(&r.playback_generation) == p.playback_generation.as_ref()
                && Some(&r.revision) == p.round_revision.as_ref()
        });
        let status = if matches || !["PREPARED", "PLAYING"].contains(&p.status.as_str()) {
            p.status
        } else {
            "CONNECTED".into()
        };
        self.sessions.get_mut(key).unwrap().browser = Some(Browser {
            client: p.client_id.clone(),
            until: now + 60000,
            seen: now,
            round: p.round_id,
            revision: p.round_revision,
            generation: p.playback_generation,
            status,
            position: p.position_ms,
        });
        self.event_seq += 1;
        Ok(
            json!({"serverTimeMs":now,"leaseExpiresAtMs":now+60000,"activeClientId":p.client_id,"heartbeatAfterMs":15000}),
        )
    }
    pub fn authorize_media(&mut self, key: &str, sha: &str, now: i64) -> Result<Media> {
        let session = self.session(key, now)?;
        self.snapshot
            .as_ref()
            .and_then(|s| s.rooms.iter().find(|r| r.room_id == session.player.room_id))
            .and_then(|r| r.round.as_ref())
            .and_then(|r| r.media_id.as_ref())
            .and_then(|id| self.media.get(id))
            .filter(|m| m.sha256 == sha)
            .cloned()
            .ok_or(Error::new(404, "MEDIA_NOT_FOUND"))
    }
}

fn text(s: &str, max: usize) -> bool {
    !s.trim().is_empty() && s.chars().count() <= max && !s.chars().any(char::is_control)
}
pub fn validate(s: &Snapshot, media: &HashMap<String, Media>, now: i64) -> Result<()> {
    if s.schema_version != 1
        || !uuid(&s.instance_id)
        || now.abs_diff(s.sent_at_ms) > 30000
        || s.rooms.len() > 1
        || s.roster.len() > 64
    {
        return Err(bad());
    }
    counter(&s.seq)?;
    let mut ids = HashSet::new();
    let mut members = HashSet::new();
    for p in &s.roster {
        if !uuid(&p.uuid)
            || !uuid(&p.membership_id)
            || !uuid(&p.room_id)
            || !text(&p.nickname, 16)
            || p.texture_hash.as_ref().is_some_and(|s| !hex(s))
            || !ids.insert(&p.uuid)
            || !members.insert(&p.membership_id)
            || !s.rooms.iter().any(|r| r.room_id == p.room_id)
        {
            return Err(bad());
        }
    }
    for room in &s.rooms {
        if !uuid(&room.room_id)
            || ![
                "IDLE",
                "WAITING_FOR_MEDIA",
                "PREPARING",
                "COUNTDOWN",
                "PLAYING",
                "PAUSED",
                "REVEAL",
                "ENDED",
            ]
            .contains(&room.phase.as_str())
        {
            return Err(bad());
        }
        counter(&room.room_revision)?;
        match (room.round_number, room.total_rounds) {
            (None, None) => {}
            (Some(number), Some(total))
                if total <= i32::MAX as u32 && (total == 0 || number <= total) => {}
            _ => return Err(bad()),
        }
        if room.phase != "REVEAL" && room.reveal.is_some() {
            return Err(Error::new(422, "ANSWER_BEFORE_REVEAL"));
        }
        if let Some(reveal) = &room.reveal {
            let id = reveal
                .youtube_url
                .strip_prefix("https://www.youtube.com/watch?v=")
                .unwrap_or("");
            if !text(&reveal.title, 160)
                || reveal.aliases.len() > 10
                || reveal.tags.len() > 10
                || reveal.aliases.iter().any(|x| !text(x, 160))
                || reveal.tags.iter().any(|x| !text(x, 32))
                || !video(id)
            {
                return Err(bad());
            }
        }
        if let Some(r) = &room.round {
            if !uuid(&r.round_id)
                || !uuid(&r.playback_generation)
                || r.number == 0
                || room.round_number.is_some_and(|number| number != r.number)
            {
                return Err(bad());
            }
            counter(&r.revision)?;
            if let Some(id) = &r.media_id {
                let asset = media
                    .get(id)
                    .ok_or(Error::retry(409, "MEDIA_NOT_READY", 2000))?;
                if r.clip_start_ms < 0
                    || r.clip_end_ms <= r.clip_start_ms
                    || r.clip_end_ms > asset.duration_ms
                    || r.clip_end_ms - r.clip_start_ms > 115000
                    || r.anchor_position_ms < r.clip_start_ms
                    || r.anchor_position_ms > r.clip_end_ms
                    || r.anchor_at_ms < 0
                    || r.anchor_at_ms > now + 30000
                {
                    return Err(Error::new(422, "INVALID_TIMELINE"));
                }
            } else if room.phase != "WAITING_FOR_MEDIA"
                && room.phase != "IDLE"
                && room.phase != "ENDED"
            {
                return Err(Error::retry(409, "MEDIA_NOT_READY", 2000));
            }
            if (room.phase == "PLAYING" && !r.playing)
                || (["IDLE", "WAITING_FOR_MEDIA", "PREPARING", "PAUSED", "ENDED"]
                    .contains(&room.phase.as_str())
                    && r.playing)
            {
                return Err(Error::new(422, "INVALID_TIMELINE"));
            }
        } else if ["PREPARING", "COUNTDOWN", "PLAYING", "PAUSED"].contains(&room.phase.as_str()) {
            return Err(Error::new(422, "INVALID_TIMELINE"));
        }
    }
    Ok(())
}
