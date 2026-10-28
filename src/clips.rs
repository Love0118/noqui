//! Disposable, memory-only Ogg views of the current quiz interval.
//! Encoded Opus packets are copied unchanged; only container timing is rewritten.
use crate::model::{Error, Result};
use axum::body::Bytes;
use ogg::{
    reading::PacketReader,
    writing::{PacketWriteEndInfo, PacketWriter},
};
use std::{
    collections::VecDeque,
    fs::File,
    io::{BufReader, Write},
    sync::Arc,
    time::{Duration, Instant},
};

const MAX_CLIP_BYTES: usize = 8 * 1024 * 1024;
const MEMORY_BUDGET: usize = 16 * 1024 * 1024;
const PREROLL: u64 = 3840; // RFC 7845: at least 80 ms at 48 kHz when seeking.

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct ClipKey {
    pub hash: String,
    pub start_ms: i64,
    pub end_ms: i64,
}
struct Entry {
    key: ClipKey,
    value: Result<Bytes>,
    until: Instant,
}
#[derive(Clone, Default)]
pub(crate) struct Clips(Arc<tokio::sync::Mutex<VecDeque<Entry>>>);
impl Clips {
    pub async fn get(&self, file: File, key: ClipKey) -> Result<Bytes> {
        // The owned guard stays with the worker if an HTTP request is cancelled.
        // Concurrent listeners share one render instead of spawning conversions.
        let mut entries = self.0.clone().lock_owned().await;
        entries.retain(|e| e.until > Instant::now());
        if let Some(i) = entries.iter().position(|e| e.key == key) {
            let entry = entries.remove(i).unwrap();
            let value = entry.value.clone();
            entries.push_back(entry);
            return value;
        }
        tokio::task::spawn_blocking(move || {
            let value = render(file, &key);
            let size = value.as_ref().map_or(0, Bytes::len);
            while entries.len() >= 8
                || entries
                    .iter()
                    .map(|e| e.value.as_ref().map_or(0, Bytes::len))
                    .sum::<usize>()
                    + size
                    > MEMORY_BUDGET
            {
                entries.pop_front();
            }
            let ttl = if value.is_ok() { 180 } else { 5 };
            entries.push_back(Entry {
                key,
                value: value.clone(),
                until: Instant::now() + Duration::from_secs(ttl),
            });
            value
        })
        .await
        .map_err(|_| unavailable())?
    }
    pub async fn retain(&self, key: Option<&ClipKey>) {
        self.0
            .lock()
            .await
            .retain(|e| Some(&e.key) == key && e.until > Instant::now());
    }
}
fn unavailable() -> Error {
    Error::new(422, "MEDIA_UNAVAILABLE")
}

// Bound allocation even if a local source file has unexpected packet sizes.
#[derive(Default)]
struct Buffer(Vec<u8>);
impl Write for Buffer {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        if self.0.len() + bytes.len() > MAX_CLIP_BYTES {
            return Err(std::io::Error::other("Clip too large"));
        }
        self.0.extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

// RFC 6716, sections 3.1 and 3.2: Opus TOC frame duration/count at 48 kHz.
fn packet_samples(packet: &[u8]) -> Result<u64> {
    let toc = *packet.first().ok_or_else(unavailable)?;
    let config = toc >> 3;
    let frame = if config >= 16 {
        120 << (config & 3)
    } else if config >= 12 {
        480 << (config & 1)
    } else {
        [480, 960, 1920, 2880][(config & 3) as usize]
    };
    let count = match toc & 3 {
        0 => 1,
        1 | 2 => 2,
        _ => packet.get(1).ok_or_else(unavailable)? & 63,
    };
    let samples = frame * u64::from(count);
    if count == 0 || samples > 5760 {
        return Err(unavailable());
    }
    Ok(samples)
}

fn render(file: File, key: &ClipKey) -> Result<Bytes> {
    if key.start_ms < 0
        || key.end_ms <= key.start_ms
        || key.end_ms - key.start_ms > 120000
        || file.metadata().map_err(|_| unavailable())?.len() > crate::MAX_SOURCE
    {
        return Err(unavailable());
    }
    let mut reader = PacketReader::new(BufReader::new(file));
    let head = reader.read_packet_expected().map_err(|_| unavailable())?;
    if !head.first_in_stream()
        || head.data.len() != 19
        || &head.data[..8] != b"OpusHead"
        || head.data[8] != 1
        || ![1, 2].contains(&head.data[9])
        || head.data[18] != 0
    {
        return Err(unavailable());
    }
    let serial = head.stream_serial();
    let original_skip = u64::from(u16::from_le_bytes(head.data[10..12].try_into().unwrap()));
    let start = key.start_ms as u64 * 48 + original_skip;
    let end = key.end_ms as u64 * 48 + original_skip;
    let tags = reader.read_packet_expected().map_err(|_| unavailable())?;
    if tags.stream_serial() != serial || !tags.data.starts_with(b"OpusTags") {
        return Err(unavailable());
    }
    let mut header = head.data;
    let mut writer = PacketWriter::new(Buffer::default());
    let mut decoded = 0u64;
    let mut base = None;
    let mut page_samples = 0u64;
    loop {
        let packet = reader.read_packet_expected().map_err(|_| unavailable())?;
        if packet.stream_serial() != serial || packet.first_in_stream() {
            return Err(unavailable());
        }
        let before = decoded;
        decoded += packet_samples(&packet.data)?;
        // Our ingested streams start at granule zero. Refuse discontinuities
        // rather than emitting a clip with an incorrect game/audio timeline.
        if packet.last_in_page() && !packet.last_in_stream() && packet.absgp_page() != decoded {
            return Err(unavailable());
        }
        if packet.last_in_stream() && (packet.absgp_page() > decoded || packet.absgp_page() < end) {
            return Err(unavailable());
        }
        if decoded <= start.saturating_sub(PREROLL) {
            continue;
        }
        let base = *base.get_or_insert_with(|| before);
        if writer.inner().0.is_empty() {
            let skip = u16::try_from(start - base).map_err(|_| unavailable())?;
            header[10..12].copy_from_slice(&skip.to_le_bytes());
            writer
                .write_packet(header.clone(), serial, PacketWriteEndInfo::EndPage, 0)
                .map_err(|_| unavailable())?;
            // Never expose source titles/answer tags in the new container.
            writer
                .write_packet(
                    b"OpusTags\x00\x00\x00\x00\x00\x00\x00\x00".as_slice(),
                    serial,
                    PacketWriteEndInfo::EndPage,
                    0,
                )
                .map_err(|_| unavailable())?;
        }
        page_samples += decoded - before;
        let done = decoded >= end;
        let boundary = if done {
            PacketWriteEndInfo::EndStream
        } else if page_samples >= 48000 {
            page_samples = 0;
            PacketWriteEndInfo::EndPage
        } else {
            PacketWriteEndInfo::NormalPacket
        };
        writer
            .write_packet(packet.data, serial, boundary, decoded.min(end) - base)
            .map_err(|_| unavailable())?;
        if done {
            return Ok(Bytes::from(writer.into_inner().0));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn source() -> (tempfile::TempDir, std::path::PathBuf) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("original.ogg");
        std::fs::write(&path, crate::test_audio::audio(10000)).unwrap();
        (dir, path)
    }
    fn key(start_ms: i64, end_ms: i64) -> ClipKey {
        ClipKey {
            hash: "source".into(),
            start_ms,
            end_ms,
        }
    }

    #[test]
    fn clip_copies_packets_with_exact_preskip_and_end_trimming_without_files() {
        let (dir, path) = source();
        let original = std::fs::read(&path).unwrap();
        for (start, end) in [(0, 1), (0, 4000), (1234, 5678), (9999, 10000), (0, 10000)] {
            let data = render(File::open(&path).unwrap(), &key(start, end)).unwrap();
            let mut reader = PacketReader::new(Cursor::new(data));
            let head = reader.read_packet_expected().unwrap();
            let skip = u64::from(u16::from_le_bytes(head.data[10..12].try_into().unwrap()));
            assert!(skip <= PREROLL + 960);
            assert_eq!(
                reader.read_packet_expected().unwrap().data,
                b"OpusTags\0\0\0\0\0\0\0\0"
            );
            let mut decoded = 0;
            loop {
                let packet = reader.read_packet_expected().unwrap();
                assert_eq!(
                    packet.data, b"\xf8\xff\xfe",
                    "encoded payload must stay unchanged"
                );
                decoded += packet_samples(&packet.data).unwrap();
                if packet.last_in_stream() {
                    assert_eq!(packet.absgp_page() - skip, (end - start) as u64 * 48);
                    assert!(decoded - packet.absgp_page() < 960);
                    break;
                }
            }
            assert!(reader.read_packet().unwrap().is_none());
        }
        assert_eq!(std::fs::read(&path).unwrap(), original);
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[tokio::test]
    async fn concurrent_listeners_share_memory_and_round_end_releases_cached_audio() {
        let (dir, path) = source();
        let clips = Clips::default();
        let mut tasks = Vec::new();
        for _ in 0..16 {
            let clips = clips.clone();
            let path = path.clone();
            tasks.push(tokio::spawn(async move {
                clips
                    .get(File::open(path).unwrap(), key(2000, 4000))
                    .await
                    .unwrap()
            }));
        }
        let first = tasks.remove(0).await.unwrap();
        for task in tasks {
            assert_eq!(first.as_ptr(), task.await.unwrap().as_ptr());
        }
        assert_eq!(clips.0.lock().await.len(), 1);
        clips.retain(None).await;
        assert!(clips.0.lock().await.is_empty());
        assert!(
            !first.is_empty(),
            "an already open response retains its own bytes"
        );
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[test]
    fn invalid_bounds_or_corrupt_audio_fail_without_full_source_fallback() {
        let (_dir, path) = source();
        for (start, end) in [(-1, 2), (2, 2), (0, 120001), (0, 11000)] {
            assert!(render(File::open(&path).unwrap(), &key(start, end)).is_err());
        }
        let mut bytes = std::fs::read(&path).unwrap();
        bytes[30] ^= 1;
        std::fs::write(&path, bytes).unwrap();
        assert!(render(File::open(path).unwrap(), &key(0, 1000)).is_err());
    }

    #[test]
    #[ignore = "requires FFmpeg (NOQUI_TEST_FFMPEG); validates real encoded/decoded samples"]
    fn real_opus_clips_decode_to_exact_sample_counts_without_temporary_cut_files() {
        use std::process::{Command, Stdio};
        let ffmpeg = std::env::var("NOQUI_TEST_FFMPEG").unwrap_or_else(|_| "ffmpeg".into());
        let encoded = Command::new(&ffmpeg)
            .args([
                "-v",
                "error",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000:duration=8",
                "-ac",
                "2",
                "-c:a",
                "libopus",
                "-b:a",
                "48k",
                "-f",
                "ogg",
                "pipe:1",
            ])
            .output()
            .unwrap();
        assert!(
            encoded.status.success(),
            "{}",
            String::from_utf8_lossy(&encoded.stderr)
        );
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("original.ogg");
        std::fs::write(&path, &encoded.stdout).unwrap();
        let decode = |bytes: Bytes| {
            let mut child = Command::new(&ffmpeg)
                .args([
                    "-v",
                    "error",
                    "-i",
                    "pipe:0",
                    "-f",
                    "f32le",
                    "-acodec",
                    "pcm_f32le",
                    "pipe:1",
                ])
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .unwrap();
            let mut input = child.stdin.take().unwrap();
            let writer = std::thread::spawn(move || input.write_all(&bytes).unwrap());
            let result = child.wait_with_output().unwrap();
            writer.join().unwrap();
            assert!(
                result.status.success(),
                "{}",
                String::from_utf8_lossy(&result.stderr)
            );
            result.stdout
        };
        let original_pcm = decode(Bytes::from(encoded.stdout));
        // Game rounds are 1–120 seconds. Tiny sub-frame intervals are checked
        // as containers above; FFmpeg 7.1 mishandles simultaneous first-packet
        // pre-skip and end discard on those intervals.
        for (start, end) in [(0, 1000), (0, 2000), (1234, 5678), (7000, 8000), (0, 8000)] {
            let clip = render(File::open(&path).unwrap(), &key(start, end)).unwrap();
            let pcm = decode(clip);
            assert_eq!(pcm.len(), (end - start) as usize * 48 * 2 * 4);
            let expected = &original_pcm[start as usize * 48 * 8..end as usize * 48 * 8];
            let error: f64 = pcm
                .chunks_exact(4)
                .zip(expected.chunks_exact(4))
                .map(|(a, b)| {
                    (f32::from_le_bytes(a.try_into().unwrap())
                        - f32::from_le_bytes(b.try_into().unwrap()))
                    .abs() as f64
                })
                .sum::<f64>()
                / (pcm.len() / 4) as f64;
            assert!(
                error < 0.002,
                "source timing differs: {start}..{end}, mean error {error}"
            );
        }
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    }
}
