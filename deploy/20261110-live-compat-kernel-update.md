# 2026-11-10 라이브 팩 적용 및 Ubuntu 보안 업데이트

사용자의 라이브 적용·서버 업데이트 요청에 따라 PuTTY `semion`에 `ubuntu`로 접속해 작업했습니다. 대상은 Ubuntu 24.04.5 LTS ARM64입니다. 조회 당시 Minecraft 접속자는 0명이었습니다.

13:36:25 KST에 Paper를 정상 종료해 플레이어·월드 저장을 확인하고, Noqui와 팩 설정을 교체한 뒤 호스트를 재부팅했습니다. 13:37:49에 Paper 시작이 완료됐으며 13:38:51의 검증에서 새 커널과 서비스를 확인했습니다. 커널·Java·플러그인을 한 번의 재부팅으로 활성화했습니다.

## 적용 결과

| 항목 | 적용 내용 |
| --- | --- |
| Noqui | `b55692ccf538e1bd17986139a94d2a1f8dfc41b8`의 성공한 CI 릴리스 |
| 실행 JAR SHA-256 | `fdd55603a912c81168f6315265ad056159480082103f43a0703917ea30a0f9b9` |
| 로비 팩 SHA-256 | `f2f62ae80765535fc8171b813199fe544f0d624c046d426bd414f284e5167d01` |
| 힌트 팩 SHA-256 | `ad26d3b08ec36c59e617edb573c4e2da512db23a86179db97897f3df979ca32d` |
| 커널 | `7.0.0-1013-oracle` → **`7.0.0-1016-oracle`** |
| 커널 패키지 | `linux-oracle-7.0`, **`7.0.0-1016.18~24.04.1`** |
| 실행 Java | Ubuntu OpenJDK `25.0.5.0.3` |
| 패키지 | 32개 업그레이드, 7개 신규 의존 패키지, 제거 없음 |

[CI 검증](https://github.com/Love0118/noqui/actions/runs/42750328179)이 성공한 [공식 프로젝트 릴리스](https://github.com/Love0118/noqui/releases/tag/beta-build-b55692ccf538e1bd17986139a94d2a1f8dfc41b8)를 서버에서 받아 SHA-256을 확인했습니다. ZIP의 SHA-1과 JAR에 내장된 팩 descriptor도 대조했습니다. 두 팩을 `/var/www/noqui/packs/`에 먼저 게시하고 공개 HTTPS 다운로드의 해시를 확인한 뒤, `plugins/Noqui.jar`와 기존 `plugins/Noqui/lobby-pack.properties`를 함께 교체했습니다.

로비 팩은 엔티티 셰이더를 덮어쓰지 않으며 기존 등 치장을 표준 아이템 디스플레이로 표시합니다. 별도 힌트 팩과 UUID는 유지했습니다. 실제 Mac 클라이언트의 다운로드·착용 외형·움직임 검증은 이번 서버 작업에 포함되지 않습니다.

Ubuntu의 기존 `noble-updates`·`noble-security` 저장소를 사용했습니다. 배포판이나 커널 계열은 변경하지 않았으며 로컬 설정을 보존했습니다. 설치 전 업데이트 시뮬레이션과 설치 후 `dpkg --audit`를 확인했습니다. 설치한 이미지·initramfs의 존재, 재부팅 후 다른 boot ID와 `uname -r`로 새 커널 실행을 검증했습니다. 남은 업그레이드와 추가 재부팅 요구는 없습니다.

루트 파일시스템의 초기 여유 공간은 약 2.3 GB였습니다. 재다운로드 가능한 APT 캐시 약 1.4 GB를 정리했고, 업데이트 후 캐시까지 정리한 최종 여유 공간은 약 3.1 GB입니다. 기존 커널과 운영 데이터는 보존했습니다.

## 최근 Linux CVE 조사

2026-11-10에 Ubuntu CVE 상태, 최근 Ubuntu 보안 공지, CISA KEV 목록과 실제 설치 패키지의 `/usr/share/doc/linux-modules-7.0.0-1016-oracle/changelog.Debian.gz`를 대조했습니다. CVE 최초 공개일과 최근 수정·악용 확인일은 다르므로, 오래된 CVE도 최근 KEV 등재 여부를 함께 확인했습니다.

| CVE | 관련 구성 요소 | 이 서버에서 확인한 근거 |
| --- | --- | --- |
| [CVE-2026-74580](https://ubuntu.com/security/CVE-2026-74580) | vhost vring | 설치 버전 변경 기록에 CVE 번호와 수정 항목 존재 |
| [CVE-2026-80844](https://ubuntu.com/security/CVE-2026-80844) | IPv6 AH 라우팅 헤더 | 설치 버전 변경 기록에 해당 경계 검사 수정 항목 존재 |
| [CVE-2026-81000](https://ubuntu.com/security/CVE-2026-81000) | TUN 수신 headroom | 설치 버전 변경 기록에 해당 크기 제한 수정 항목 존재 |
| [CVE-2026-68121](https://ubuntu.com/security/CVE-2026-68121) | PPPoE 해제 후 사용 | 설치 버전 변경 기록에 CVE 번호와 수정 항목 존재 |
| [CVE-2026-74469](https://ubuntu.com/security/CVE-2026-74469) | SCTP 개수 오버플로 | 설치 버전 변경 기록에 CVE 번호와 수정 항목 존재 |
| [CVE-2026-89492](https://ubuntu.com/security/CVE-2026-89492) | OCFS2 메타데이터 경계 검사 | 설치 버전 변경 기록에 해당 검증 수정 항목 존재 |
| [CVE-2026-31431](https://ubuntu.com/security/CVE-2026-31431) | Copy Fail | Ubuntu는 Noble `linux-oracle-7.0`을 영향 없음으로 분류 |
| [CVE-2026-46300](https://ubuntu.com/security/CVE-2026-46300) | Fragnesia, skb 공유 조각 | 최근 CISA KEV 등재. Ubuntu는 이 커널을 영향 없음으로 분류하며 이전 패키지 변경 기록에도 관련 수정이 존재 |
| [CVE-2026-53266](https://ubuntu.com/security/CVE-2026-53266) | ebtables 메모리 쓰기 | 최근 CISA KEV 등재. Ubuntu의 해당 커널 수정 버전은 `7.0.0-1011.11~24.04.1`이며 실행 버전은 그 이후 |
| [CVE-2025-39964](https://ubuntu.com/security/CVE-2025-39964) | 커널 경쟁 조건 | 최근 CISA KEV 등재. Ubuntu는 이 커널을 영향 없음으로 분류 |
| [CVE-2025-39682](https://ubuntu.com/security/CVE-2025-39682) | 커널 TLS | 최근 CISA KEV 등재. Ubuntu는 이 커널을 영향 없음으로 분류 |

일부 Ubuntu CVE 페이지는 `7.0.0-1016.18~24.04.1`을 아직 `work in progress`로 표시합니다. 설치 가능한 서명 패키지의 변경 기록에서 수정 항목이 확인된 사실과, 공식 CVE 상태가 갱신 중인 사실을 구분해 기록했습니다. 위 표는 패키지·변경 기록 검증 결과이며 개별 취약점의 공격 재현이나 전체 시스템의 취약점 부재를 보증하는 결과는 아닙니다. 별도 모듈 차단이나 방화벽·SSH 정책 변경은 수행하지 않았습니다.

최근 패키지 업데이트 공지와 대조할 때는 [Ubuntu 커널 공지](https://ubuntu.com/security/notices/USN-8959-1), [해당 Oracle 패키지 변경 기록](https://lists.ubuntu.com/archives/noble-changes/2026-October/060472.html), [CISA KEV 원본](https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json)을 기준으로 했습니다. Ubuntu 24.04의 generic 커널 수정 버전을 Oracle ARM64 커널 버전과 혼동하지 않았습니다.

## 재시작 후 검증과 복구 자료

- Minecraft, Noqui 웹, 임시 미디어 전달, Nginx, 포탈 채팅 미러, 기존 네트워크 모니터 서비스가 모두 `active`입니다. 실패한 systemd unit은 없습니다.
- Paper 26.3의 상태 응답과 Noqui 활성화 로그를 확인했습니다. 새 기동 로그의 ERROR·Exception은 없습니다.
- 공개 HTTPS 및 `127.0.0.1:8787/healthz`는 HTTP 200입니다. 인증된 내부 플레이리스트 조회도 HTTP 200이며 기존 6개 목록이 보존돼 있습니다.
- 무인증 내부 API는 401, 공개 경로의 내부 API는 404, 무인증 미디어 요청은 401입니다.
- `config.yml`, `credits.json`, `lobby/wallets.bin`은 정지 중 백업과 재시작 후 SHA-256이 같습니다. 지갑·출제권·설정을 초기화하지 않았습니다.
- 게임·오디오의 실제 참가자 재생이나 Mac GPU 화면을 제어하는 검증은 하지 않았습니다.

이전 JAR·팩 설정·관련 플러그인 데이터는 서버의 `/home/ubuntu/mc-luck-defense-deploy/compat-kernel-20261110/rollback/`에 보관했습니다. 동일 작업 폴더에 APT 로그, 재부팅 전 패키지 목록, 커널 변경 기록 대조, 배포 및 검증 영수증이 있습니다. 키·쿠키·서명 헤더는 기록하지 않았습니다.

[배포·검증 영수증](20261110-live-compat-kernel-update.json)
