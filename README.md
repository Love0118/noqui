# Noqui web

노래퀴즈의 Rust 오디오 서비스와 브라우저 재생 페이지입니다. 이 체크아웃은 [Love0118/noqui의 web 브랜치](https://github.com/Love0118/noqui/tree/web)이며 Paper 플러그인과 리소스팩은 [beta 브랜치](https://github.com/Love0118/noqui/tree/beta)에서 관리합니다.

## 개발과 빌드

```powershell
git clone --branch web --single-branch https://github.com/Love0118/noqui.git noqui-web
cd noqui-web
cargo test --locked
node --test tests/browser.cjs
python -m unittest discover -s tools -p 'test_*.py'
cargo build --release --locked
```

Rust stable, Node.js 22 이상, Python 3이 필요합니다. 서비스 운영에는 FFmpeg, FFprobe, yt-dlp 및 JavaScript 런타임도 필요합니다. 빠른 브라우저 검사는 Node 기본 모듈만 사용합니다. 실제 Chrome 자동재생 검사는 Playwright를 설치한 환경에서 `node tests/autoplay.cjs`로 따로 실행하며 [AUTOPLAY.md](AUTOPLAY.md)를 따릅니다.

프론트엔드는 `static/`, HTTP·인증·미디어·변환·캐시는 `src/`, 독립 서비스와 브라우저 검사는 `tests/`에 있습니다. 원래 `mc-luck-defense/noqui/web`의 변경 이력을 보존했습니다. 서로 다른 구성 요소를 담는 `beta`와 `web` 브랜치는 통째로 병합하지 않습니다.

## 플레이리스트 설치

Paper의 `/noqui playlist`는 내부 HMAC API로 설치 목록을 받아 선택한 목록을 셔플로 시작합니다. 전체 길이 음원은 `data/library/<id>/audio/`에 보관하며 기존 `media/`의 5 GiB LRU 용량 계산·삭제와 분리합니다. 브라우저는 기존 회원 인증과 현재 라운드 권한으로만 접근합니다.

서비스를 정지한 뒤 서비스 계정으로 `install-playlist /var/lib/noqui /전송경로/playlist.json`을 실행하고 재시작합니다. 릴리스에 설치기를 함께 제공합니다. 원본 번들은 `beta`의 `tools/build_playlist.py`로 검수 완료곡만 내보내며, 정답이 들어 있는 오디오 태그를 제거하고 전체 재생 길이를 보존합니다. 설치 시 메타데이터·SHA-256·크기·Opus 길이와 안전한 상대 경로를 검사한 후 목록 전체를 원자적으로 게시합니다. 이미 있는 ID를 덮어쓰지 않습니다. 교체 시 기존 폴더를 library 밖으로 보관한 후 설치합니다. library를 nginx 정적 디렉터리로 공개하지 마세요.

## 배포

GitHub Actions가 Rust·브라우저·쿠키 격리 검사를 실행한 후 Ubuntu ARM64에서 릴리스 바이너리를 빌드합니다. `web-build-<commit>` 릴리스의 `noqui-web-linux-arm64.tar.gz`에는 바이너리, 다운로드 래퍼와 배포 예제가 포함됩니다. 소스 변경만으로 운영 서비스가 자동 재시작되지는 않습니다.

[운영 안내](deploy/REVIEW.md)와 [systemd 예제](deploy/noqui-web.service.example)를 참고합니다. 바이너리는 `/opt/noqui/releases/<release>/`에 배치하고 `/opt/noqui/current`로 선택합니다. 환경설정과 브리지 키는 `/etc/noqui/`, DB·5 GiB LRU 오디오 캐시는 `/var/lib/noqui/`에 보관합니다. 키·쿠키·DB·브라우저 프로필은 Git이나 릴리스에 넣지 않습니다. 기존 서비스와 Moma BGM의 쿠키 갱신기는 운영 중인 경로를 유지합니다.

사이트는 `https://noqui.danta.qzz.io`, nginx 대상은 `127.0.0.1:8787`, Paper 전용 HMAC 브리지는 `127.0.0.1:8788`입니다. 브리지 계약은 [beta 브랜치의 contracts.md](https://github.com/Love0118/noqui/blob/beta/contracts.md)를 기준으로 확인합니다. Paper 플러그인과 별도로 빌드·릴리스하되 계약 변경은 호환성을 함께 검증합니다.
