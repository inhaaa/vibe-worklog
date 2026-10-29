# 공개 시트/V14 장시간 부하·전체 취약점 후속 실행 계획

> **For agentic workers:** Use superpowers:subagent-driven-development for the independent inventory/load tasks and verification-before-completion for each result. 사용자는 검사·발견 수정·단위별 Commit/일반 Push·필요 배포를 승인했다.

**Goal:** 공개 시트/V14 최종 소스의 33분 Linux 혼합 HTTP 부하와 전체 의존성·이미지 취약점 검사를 실제 수행하고, 적용 가능한 수정과 필요한 공개 반영을 검증한다.

**Architecture:** 기존 HTTP fixture/runner·SBOM/Grype 도구를 재사용한다. 공개 VPS에는 부하를 보내지 않고 전용 로컬 Linux 앱/합성 DB를 새로 만들며 기존 로컬·공개 서비스의 컨테이너/자료/권한/HTTP를 보존한다. public-demo의 고정 공유 계정과 일반 local 프로필의 3테넌트18세션은 인증을 완화하지 않고 범위를 나눈다.

**Tech Stack:** 기존 Java25/Maven3.9.16·React/AG Grid36.2.0·PostgreSQL18.6 noXML·Docker Linux·Python stdlib·Syft1.55.0/Grype0.121.0 및 새 고정 취약점 DB.

## 1. 기준선·격리

- [x] 공개 `12f0f78`·V14와 로컬/공개 서비스 ID·이미지·시작/실행 상태·선별 포트/볼륨/권한·기초 자료 지문을 읽기 전용으로 기록한다. 소스 시작은 `bf93fcc`이며 제품과 `12f0f78`의 차이는 문서뿐인지 확인한다.
- [x] 기존 `codex/public-sheet-release` worktree를 재사용한다. 다른 원본/연간 작업·비밀 환경·전역 Docker/신뢰/설정을 변경하지 않는다.
- [x] 새 증거는 ignored `target/sheet-soak-security-20261029/{scan,load-run}`에 보관한다. 비밀번호·CSRF·원문 행·전체 inspect/env는 출력/커밋하지 않는다.
- [x] 공개 배포본은 제한된 읽기 전용 패키지/바이너리/자산 명세를 확인하고, 로컬 재빌드의 같은 범위와 대조한다. 재빌드 ID와 실제 공개 ID를 구분한다.

## 2. 전체 취약점 검사와 수정

- [x] 새 캐시로 공식 Grype DB를 갱신·검증하고 built/schema/출처/DB SHA를 고정한다. 기존 frozen DB를 덮지 않는다. 외부 SBOM/이미지 업로드·사용자 ignore/VEX 없이 검사한다.
- [x] npm production/dev 전체 audit, Java runtime/test/provided 전체 의존성 PURL·실제 JAR, 해석된 plugin JAR, Maven lib/boot·Wrapper·소스 vendored 자원을 구분해 검사한다.
- [x] 최신 app/migrate/Java builder/Node builder/일반 PG/Java 회귀 PG/실제 부하 PG/시트 검증 PG/Ryuk9역할을 불변 ID로 고정하고 squashed SBOM·실제 image manifest 사슬·원시 검사·hash를 남긴다. 과거 이미지의 검사 수치를 재사용하지 않는다.
- [x] 새/유지 CVE를 공급자 공식 수정 상태·설치 버전·실제 소비 경계에 대조한다. 공급 가능한 호환 수정은 실패 재현→최소 수정→관련/필수 회귀→문서→Commit/Push 순서로 처리한다. 공급 미정·오매치·조건부 경고를 실제 패치와 구분하며 원시 경고를 숨기지 않는다.
- [x] OS/Java/Node/PG 도구 변경 시 기존 라이선스·고정 checksum·read-only/nonroot/cap drop/NNP·loopback·RLS/독립 역할을 유지한다. 새 PG 이미지는 백업·독립 복원·자료/권한 보존을 먼저 확인한다.

## 3. 최종 부하

- [x] 최종 수정 소스의 Linux 이미지와 현재 V14 test classpath를 고정한다. 새 소유 DB에3테넌트×300명·18세션·과거 원천55,800건을 준비한다. `local` 프로필의 일반 인증·권한·RLS를 유지하며 `public-demo`와 혼합하지 않는다.
- [x] app/DB 각각 loopback 단일 게시·전용 bridge/gateway `/32`·자원 제한·관리 자격 미주입을 실제 inspect로 확인한다. 기존 helper의 과거 SHA·이미지·receipt·고정35테이블 계약은 최신 동적/V14 계약으로 대체한다.
- [x] HTTP24개·multipart fields/file 각6개·60초 smoke·3테넌트 재계산/독립 승인/Excel·불변 재다운로드·출근 명령 재시도를 먼저 검증한다. 새 시트 bulk/코드 경로는 별도 결정적 HTTP·현재 Java/PG/프론트 회귀로 검증해 기존21연산 부하와 구분한다.
- [x] 총1,980초·목표8HTTP req/s·60초 ramp의 기존21연산을 실제 실행하고15초 간격 app/DB 자원·SQL 표본을 수집한다. 대형 빌드/스캔과 겹치지 않는다. 목표/비중을 조용히 바꾸지 않는다.
- [x] elapsed·전송/예상 거부/실패·연산별/steady/분별 지연·도달률을 원시 요청에서 재계산한다. 요청/승인/원장·연차 차감·정책 참조·원천 중복/허용IP·Excel fingerprint와 신규4테이블을 검사한다. 종료 마감의 대기 신청을 실패와 구분한다.
- [x] 앱 정지 후 read-only repeatable-read 정합성을 검사하고 소유 fixture/app/network만 정상 종료한다. 기존 로컬·공개 서비스 지문/HTTP를 다시 비교한다.

## 4. 배포·완료 기록

- [x] 수정이 필요한 경우만 공개 보안을 보존해 통합·Commit/Push하고 [WorkLog 전용 절차](../../development/worklog-public-updates.md)로 배포한다. 새 migration/PG 변경은 별도 백업·독립 복원·보존 검증 없이 교체하지 않는다. 다른 서비스/DNS/Caddy/전역 설정은 변경하지 않는다.
- [x] 실제 공개 SHA/이미지·health·72개 공개 보안/메뉴·4개 프록시 경계·변경 화면과 기존 서비스 보존을 확인한다. 재배포 불필요 시 그 근거를 기록한다.
- [x] `docs/quality/sheet-soak-security-20261029.md`에 최종 소스/실행 시각·33분 실제 지표·검사 대상/DB·원시/고유 CVE·수정/공급 대기·한계·미실시를 기록하고 README/개발 안내에 연결한다. 전체 검사 완료를 CVE0·운영 SLA·호스트 침투 검증으로 확대하지 않는다.
- [x] 각 완성 단위는 실제 검증·git status/diff 후 작은 Commit/일반 Push로 마무리한다. 마지막에 작업트리·원격 반영과 실행하지 않은 범위를 확인한다.


## 실제 완료 기록

- 계획 `9923e78`, 실행 중 발견한 승인함 테스트 계약 수정 `c33d26f`, 전체 취약점/공식 OS 분류 기록 `2e023c2`는 각각 검증 후 일반 Push했다. 최종 측정 기준은 `c33d26f`이며 제품 Java/DB/프론트/의존성 변경은0이다.
- 새33분1,980.383초·15,465요청·실패0·p95 323.998ms·steady7.996322req/s,21연산·Docker132/SQL131표본과 종료 원장/원천/Excel/V14 정합성을 확인했다. 첫 실패 trial과 사전 게이트 오류는 별도 보존하고 최종 성공으로 덮지 않았다.
- Java656·공개 통합React247·Python74, 실제 HTTP24·multipart12·별도 시트135항목을 통과했다. 시트는 별도 DB로43표94,101개 원행 보존·예상34행 추가·정상 종료/PG 자동 정리를 확인했다.
- 최종9역할 원시2,676행·전체10회 검사3,085행, OS2,362행 공식 분류·추가 적용 패치 후보0·미해결 위험을 기록했다. 원시는 ignored target에 보존하고 공식 판정 CSV는 저장소에 남겼다.
- 기본 로컬 DB43표/권한·기존 컨테이너22개와 공개17개·Caddy/기존 사이트·V14 업무43표407행의 지문을 보존했다. 이번 소유 자원만 정확 ID/라벨로 정리했다. 공개72+4항목도 재검증했다.
- 제품/runtime/기존 DB 변경이 없어 신규 백업/복원·마이그레이션·재배포 조건은 발생하지 않았다. 공개 `12f0f78`·V14를 유지했다. 새 PC/모바일 시각 회귀·VPS 지속 부하·호스트 전체/침투·운영 SLA는 이번 검사로 주장하지 않는다.
- [최종 부하·보존 보고서](../../quality/sheet-soak-security-20261029.md), [전체 취약점 보고서](../../quality/sheet-security-20261029.md), [공식 OS 분류](../../quality/sheet-security-20261029-os.csv)를 따른다. 일반 개발본에는 공개 보안 구현을 전체 merge하지 않고 공용 테스트 수정/기록만 동기화한다.
- 일반 개발본에는 같은 공용2파일 SHA를 선택 반영하고 프로젝트 JDK25로 Python67개를 재검증했다. 공개74개와의 차이7개는 공개 배포 전용이다. 기본 PATH Java8로 인한 최초 setup 실패 기록을 보존했고 전역 환경은 변경하지 않았다.
