use ogg::writing::{PacketWriteEndInfo, PacketWriter};

// Valid 20 ms Opus silence packets in a stereo Ogg stream, including encoder
// pre-skip and a partially used final packet. No external encoder is needed.
pub fn audio(duration_ms: i64) -> Vec<u8> {
    let mut writer = PacketWriter::new(Vec::new());
    let mut head = b"OpusHead\x01\x02\x38\x01\x80\xbb\x00\x00\x00\x00\x00".to_vec();
    head[10..12].copy_from_slice(&312u16.to_le_bytes());
    writer
        .write_packet(head, 42, PacketWriteEndInfo::EndPage, 0)
        .unwrap();
    let comment = b"ANSWER=Private Title";
    let mut tags = b"OpusTags".to_vec();
    tags.extend_from_slice(&0u32.to_le_bytes());
    tags.extend_from_slice(&1u32.to_le_bytes());
    tags.extend_from_slice(&(comment.len() as u32).to_le_bytes());
    tags.extend_from_slice(comment);
    writer
        .write_packet(tags, 42, PacketWriteEndInfo::EndPage, 0)
        .unwrap();
    let end = duration_ms as u64 * 48 + 312;
    for i in 1..=end.div_ceil(960) {
        let pos = i * 960;
        let info = if pos >= end {
            PacketWriteEndInfo::EndStream
        } else if i % 50 == 0 {
            PacketWriteEndInfo::EndPage
        } else {
            PacketWriteEndInfo::NormalPacket
        };
        writer
            .write_packet(b"\xf8\xff\xfe".as_slice(), 42, info, pos.min(end))
            .unwrap();
    }
    writer.into_inner()
}
