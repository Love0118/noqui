# 2026-11-10 라이브 서비스 권한 분리

2026-11-10 14:17 KST 검증 완료. Minecraft를 정상 저장 후 한 차례 재시작했고 14:13:51에 준비를 마쳤다. 웹과 오디오 서비스도 새 계정으로 재시작했다.

| 서비스 | 실행 계정 |
| --- | --- |
| `mc-luck-defense.service` | `minecraft` |
| `mc-luck-defense-youtube-cookies.service` | `minecraft` |
| `noqui-web.service` | `noqui` |
| `noqui-media-bypass.service` | `noqui` |

두 계정은 잠긴 비밀번호와 `nologin` 셸을 사용하고, sudo 권한과 `sudo`·`lxd` 그룹 소속이 없다. SSH에서도 두 계정의 접속을 명시적으로 차단한다. `ubuntu`는 기존 관리자 계정으로 유지한다.

각 서비스의 `99-security.conf` drop-in에 `NoNewPrivileges=yes`, 빈 `CapabilityBoundingSet`, `ProtectSystem=strict`, 전용 임시 디렉터리와 장치·커널 설정 보호를 적용했다. Minecraft와 쿠키 갱신 서비스는 `ProtectHome=tmpfs`와 게임 디렉터리의 `BindPaths`를 사용한다. 기존 절대 경로를 유지하면서 관리자 홈의 나머지 내용은 감춘다. 웹과 오디오 서비스에는 `ProtectHome=yes`를 적용했다. 이 조합은 [systemd 실행 환경 문서](https://www.freedesktop.org/software/systemd/man/258/systemd.exec.html)의 디렉터리 노출 방식에 따른다.

게임 디렉터리 `/home/ubuntu/mc-luck-defense`는 `minecraft`, 웹 상태 디렉터리 `/var/lib/noqui`는 `noqui` 소유로 변경했다. 오디오 서비스에서는 웹 상태 디렉터리를 읽기 전용으로 사용한다. `/opt/noqui`의 다운로더와 래퍼는 root 소유로 변경했다.

공유 YouTube 쿠키의 현재 경로는 `/var/lib/noqui-cookies/youtube-cookies.txt`이다. 디렉터리는 `minecraft:noqui-shared`·`2750`, 파일은 `0640`이다. Minecraft와 웹이 읽을 수 있고 웹은 수정할 수 없다. 웹 환경 파일, BGM 설정, 쿠키 갱신 서비스가 이 경로를 사용한다. 갱신 도구는 교체 파일에도 `0640`을 적용하고 이전 쿠키 백업은 `0600`으로 유지한다. 브리지 키는 root 소유와 공유 그룹의 읽기 권한만 유지하며, 웹 환경 파일은 `root:noqui`·`0640`이다.

HTTPS에는 `Strict-Transport-Security: max-age=31536000`을 적용하고 nginx 버전 표시를 숨겼다. TLS 1.2는 ECDHE와 AES-GCM 또는 ChaCha20-Poly1305만 허용하며 TLS 1.3도 유지한다. HSTS는 웹 호스트에만 적용하며 `includeSubDomains`와 `preload`는 사용하지 않는다. nginx 1.24의 헤더 상속 규칙에 맞춰 자체 `add_header`가 있는 정적 파일과 리소스팩 location에도 HSTS를 명시했다. [nginx 헤더 문서](https://nginx.org/en/docs/http/ngx_http_headers_module.html), [TLS 문서](https://nginx.org/en/docs/http/ngx_http_ssl_module.html).

검증 결과:

- 주요 서비스 6개 모두 active, 실패한 systemd 서비스 0개, 변경한 상시 서비스의 자동 재시작 횟수 0회.
- 실제 프로세스 UID 분리, `NoNewPrivs=1`, effective/bounding capability 모두 0 확인. 두 계정의 sudo 명령 실행도 거부됨.
- 게임 계정으로 서명한 브리지 요청 HTTP 200, 재생목록 6개 확인. 웹의 게임 디렉터리 접근과 게임의 웹 상태 디렉터리 접근은 차단됨.
- 오디오 참조 1,632개 모두 새 계정에서 읽기 가능. 웹 다운로더의 YouTube 메타데이터 추출과 실제 쿠키 갱신 작업 성공.
- 웹과 상태 확인 HTTP 200, 인증 없는 상태·미디어 요청 401, 공개 브리지 경로 404 유지. 리소스팩 범위 요청 206 및 캐시 정책 유지.
- 외부에서 CBC 암호군 3개 거부, AES-GCM·ChaCha20·TLS 1.3 성공. 정상·오류·정적 파일 응답의 HSTS와 nginx 버전 비공개 확인.
- Noqui 설정·출제권·지갑·JAR·리소스팩 설정과 재생목록 manifest의 변경 전후 해시 일치.

실제 사용자 세션의 브라우저 재생과 스피커 출력은 이번 검증 범위에 포함하지 않았다. 커널은 앞서 업데이트한 `7.0.0-1016-oracle`을 유지한다.

향후 게임 파일 배포에는 관리자 계정에서 sudo를 사용해 `minecraft:minecraft` 소유권을 지정해야 한다. 웹 데이터는 `noqui:noqui`, `/opt/noqui`의 실행 파일은 root 소유로 유지한다. 기존의 일회성 배포 기록을 재실행해 `ubuntu` 실행 계정을 복원하지 않는다. 쿠키 갱신은 `sudo systemctl start mc-luck-defense-youtube-cookies.service`로 실행할 수 있다.

원본 설정·소유권 기록과 복구 스크립트는 서버의 root 전용 `/var/backups/noqui-security-20261110/`에 보관했다. 필요한 경우 `sudo python3 /var/backups/noqui-security-20261110/rollback.py`로 기존 설정과 소유권을 복원한다. 검증 완료 후 자동 복구 타이머는 해제했다. 비밀값과 원본 보안 점검 증거는 저장소에 포함하지 않았다.
