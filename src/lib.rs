pub mod api;
pub mod auth;
mod cache;
pub mod catalogue;
pub mod jobs;
pub mod model;
pub mod state;

use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::broadcast;

pub const ORIGIN: &str = "https://noqui.danta.qzz.io";
pub const PROFILE: &str = "opus-48-clip120-source360-v3";
pub const PREVIOUS_PROFILE: &str = "opus-48-clip40-source360-v2";
pub const MAX_SOURCE_SECONDS: u32 = 360;
pub const CLIP_SECONDS: u32 = 120;
pub const MAX_SOURCE: u64 = 25 * 1024 * 1024;
pub const CACHE_BUDGET: u64 = 5 * 1024 * 1024 * 1024;

pub trait Clock: Send + Sync {
    fn now_ms(&self) -> i64;
}
pub struct SystemClock {
    epoch: i64,
    start: Instant,
}
impl Default for SystemClock {
    fn default() -> Self {
        Self {
            epoch: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64,
            start: Instant::now(),
        }
    }
}
impl Clock for SystemClock {
    fn now_ms(&self) -> i64 {
        self.epoch + self.start.elapsed().as_millis() as i64
    }
}

#[derive(Clone)]
pub struct Config {
    pub secret: [u8; 32],
    pub key_id: String,
    pub data: PathBuf,
    pub yt_dlp: String,
    pub ffmpeg: String,
    pub ffprobe: String,
    pub cookies: Option<PathBuf>,
}

#[derive(Clone)]
pub struct App {
    pub config: Arc<Config>,
    pub clock: Arc<dyn Clock>,
    pub inner: Arc<Mutex<state::State>>,
    pub jobs: jobs::Jobs,
    pub changed: broadcast::Sender<()>,
    pub control_sync: Arc<tokio::sync::Mutex<()>>,
}
impl App {
    pub fn open(config: Config, clock: Arc<dyn Clock>) -> Result<Self, String> {
        let jobs = jobs::Jobs::open(&config.data, clock.now_ms())?;
        let (changed, _) = broadcast::channel(32);
        Ok(Self {
            config: Arc::new(config),
            clock,
            inner: Arc::new(Mutex::new(state::State::default())),
            jobs,
            changed,
            control_sync: Arc::new(tokio::sync::Mutex::new(())),
        })
    }
    pub fn now(&self) -> i64 {
        self.clock.now_ms()
    }
    pub fn notify(&self) {
        let _ = self.changed.send(());
    }
}
