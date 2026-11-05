# うた - 作別

[요청한 YouTube 목록](https://www.youtube.com/playlist?list=PLjY209iDQPjxVu3FVtbL61lyU-CnwQUlF)을 2026-11-05에 수집했습니다. 원래 602개 항목 중 같은 영상 ID가 한 번 중복되어 601개를 보존합니다. `sourceIndex`, `sourceTitle`, `originalVideoId`, `recordingHistory`로 원래 순서와 교체 이력을 확인할 수 있습니다.

- 서버 ID: `uta-pljy209`, 표시 이름: `うた - 作別`
- 메타데이터 검수 완료 549곡, 검토 대기 52곡
- 전체 길이 음원 확보 590곡, 미확보 11곡
- **서버 재생 가능 541곡**: 검수 완료와 음원 확보를 모두 만족하는 항목
- 한국어 통용 제목·발음 추가 확인 228곡. 이 중 재생 가능 목록에 포함된 199곡은 출처로 확인한 원제·영문·로마자 제목을 유지합니다. 직접 번역하거나 새 발음 태그를 만들어 채우지 않았습니다.

8개 `gpt-6-luna` 배치의 제안을 메인 세션에서 검토했습니다. 영상 ID가 정확히 일치하는 VocaDB 375건, 유통사·제작자 설명의 작사·작곡 역할, 기존 검수 자료의 동일 곡 연결과 개별 출처를 대조했습니다. 다른 곡으로 이동하는 크레딧 링크와 재확인할 수 없는 이름은 검수 근거에서 제외하거나 검토 대기로 돌렸습니다. `main-review.json`은 배치 해시, 메인 판단과 오디오 검사를 기록합니다. 배치의 최초 검토 수와 최종 승인 수는 다릅니다.

다운로드 실패 원본 중 9개는 같은 곡·가수의 전체 녹음으로 교체했습니다. 원본별 UUID와 원래 영상 ID는 보존합니다. 서로 다른 원본 항목 두 개가 같은 `Oddloop` 녹음을 사용하므로 음원 확보 590개 항목의 실제 파일은 589개입니다. 이 589개 파일은 FFmpeg로 끝까지 디코딩했고, 크기·SHA-256·전체 길이·Opus·내장 제목·작가·정답 태그도 확인했습니다. 이 검사는 모든 녹음을 직접 청취했다는 뜻은 아닙니다.

`writers`는 작사·작곡 크레딧, `singer`는 녹음 보컬, `aliases`는 출처가 있는 이름·발음 및 특수문자 제거 표기입니다. 게임에서 사용할 수 있는 표기만 최대 10개 `tags`에 합칩니다. 원어 작가명만 확인된 경우 새 한국어 표기를 추정하지 않습니다. 기존에 승인된 별칭의 출처·승인 이력도 유지합니다.

- `catalogue.json`: 전체 원본 목록, 크레딧, 별칭, 출처, 음원 및 검수 상태
- `review-required.json`: 플레이 대상에서 제외한 메타데이터 검토 목록
- `naming-review-required.json`: 한국어 통용 표기 추가 확인 목록
- `download-failures.json`: 미확보 음원과 실패 분류
- `summary.json`, `main-review.json`: 최종 집계와 검수 기록
- [`../../playlists/catalogue.sqlite3`](../../playlists/catalogue.sqlite3): 통합 SQLite DB의 `플레이리스트`, `전체곡`, `재생가능곡`, `검토필요곡`, `이름검토필요곡`, `별칭목록`, `출처목록` 뷰

원본 정제 음원의 `audio/`는 전체 길이 약 48kbps 스테레오 Ogg Opus이며 Git에서 제외합니다. 서버 내보내기는 [`../../playlists/uta-pljy209/playlist.json`](../../playlists/uta-pljy209/playlist.json)입니다. 서버용 파일은 정답이 들어 있는 오디오 코멘트를 제거합니다. 설치된 `library/`는 기존 5 GiB 임시 캐시 계산·삭제 대상에서 제외합니다.

```powershell
python tools/validate_playlist_catalogues.py --audio catalogues/uta-PLjY209iDQPjxVu3FVtbL61lyU-CnwQUlF/catalogue.json
python tools/build_playlist_database.py
# 새 내보내기 경로를 사용합니다. 기존 폴더에는 덮어쓰지 않습니다.
python tools/build_playlist.py catalogues/uta-PLjY209iDQPjxVu3FVtbL61lyU-CnwQUlF/catalogue.json <새-폴더> --id uta-pljy209 --name "うた - 作別" --ffmpeg <ffmpeg.exe>
```

OP는 `/noqui playlist`에서 선택하거나 `/noqui playlist uta-pljy209`로 시작합니다. 기본 셔플과 `/noqui limit <라운드 수>`의 뒤 대기곡 잘라내기를 그대로 사용합니다. 라이브 설치 결과는 `deploy/20261105-uta-playlist.json`에 기록합니다.
