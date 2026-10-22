use noqui_web::{App, Config, SystemClock, api, jobs};
use std::{path::PathBuf, sync::Arc};
#[tokio::main]
async fn main() {
    if let Err(code) = start().await {
        eprintln!("noqui: {code}");
        std::process::exit(1)
    }
}
async fn start() -> Result<(), &'static str> {
    let secret_path = std::env::var_os("NOQUI_BRIDGE_SECRET_FILE")
        .ok_or("NOQUI_BRIDGE_SECRET_FILE is required")?;
    let secret: [u8; 32] = std::fs::read(secret_path)
        .map_err(|_| "bridge secret is unreadable")?
        .try_into()
        .map_err(|_| "bridge secret must contain exactly 32 raw bytes")?;
    let config = Config {
        secret,
        key_id: std::env::var("NOQUI_KEY_ID").unwrap_or_else(|_| "primary".into()),
        data: PathBuf::from(std::env::var_os("NOQUI_DATA_DIR").unwrap_or_else(|| "data".into())),
        yt_dlp: std::env::var("NOQUI_YT_DLP").unwrap_or_else(|_| "yt-dlp".into()),
        ffmpeg: std::env::var("NOQUI_FFMPEG").unwrap_or_else(|_| "ffmpeg".into()),
        ffprobe: std::env::var("NOQUI_FFPROBE").unwrap_or_else(|_| "ffprobe".into()),
        cookies: std::env::var_os("NOQUI_YOUTUBE_COOKIES_FILE").map(PathBuf::from),
    };
    let app = App::open(config, Arc::new(SystemClock::default()))
        .map_err(|_| "storage initialization failed")?;
    let public = tokio::net::TcpListener::bind("127.0.0.1:8787")
        .await
        .map_err(|_| "public loopback bind failed")?;
    let internal = tokio::net::TcpListener::bind("127.0.0.1:8788")
        .await
        .map_err(|_| "internal loopback bind failed")?;
    let worker = tokio::spawn(jobs::worker(app.clone()));
    let shutdown = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    tokio::select! {
        result=axum::serve(public,api::public(app.clone()))=>{result.map_err(|_|"public listener stopped")?;},
        result=axum::serve(internal,api::internal(app))=>{result.map_err(|_|"internal listener stopped")?;},
        _=shutdown=>{},
    }
    worker.abort();
    let _ = worker.await;
    Ok(())
}
