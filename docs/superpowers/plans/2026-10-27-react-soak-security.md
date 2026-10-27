# React 최종 이미지 장시간 부하·전체 이미지 취약점 검사

> **For agentic workers:** HTTP 부하 도구, 이미지 검사, fixture·multipart 검증의 담당 파일을 분리해 병렬 준비한다. 실제 DB/앱 실행과 장시간 부하·빌드·회귀의 자원 순서는 루트가 관리한다.

**Goal:** 사용자 승인에 따라 현재 React 실행물의 장시간 HTTP 부하와 전체 프로젝트 이미지 취약점 검사를 수행하고, 발견한 문제를 수정·재검증한다.

**Architecture:** 기존 전용 Testcontainers fixture와 loopback Linux app을 재사용한다. 기존 기본 DB/app과 데이터는 보존한다. 현재 JSON 화면 계약에 맞게 부하·multipart 도구를 TDD로 갱신하고, 스캐너 결과는 공식 수정판·실제 포함 여부·도달성·공급 대기를 구분한다.

**Tech Stack:** 기존 Python 표준 라이브러리, Java25·Maven·PostgreSQL18.6·Docker, 전용 Syft/Grype. 새 앱 라이브러리는 필요성이 확인된 보안 수정에 한정한다.

## 수용 계약

- 실제 최종 Linux app에서 합성3고객사×300명과 로그인18세션을 사용한다. `http-mixed-soak.py`의 기존21개 연산·읽기/쓰기·CSRF·명령 재전송·독립 승인·재계산·불변 XLSX·타 테넌트/권한 거부·종료 정합성을 유지한다.
- 목표8 HTTP 요청/초, 총1,980초(33분), ramp60초 후 steady1,920초(32분)를 실제 실행한다. 준비·짧은 smoke·실패/중단 표본은 최종 표본에 합산하지 않는다. 목표 미달·누락·예상 거부·논리 실패를 각각 기록한다.
- 부하 중15초 간격 Docker CPU/메모리/PID·OOM/restart 및 동일 전용 DB의 연결/대기/크기를 관측한다. 대형 스캔·빌드·전체 회귀와 steady 구간을 겹치지 않는다.
- app·migrate·Java build·Node frontend build·PostgreSQL·Ryuk의 실제 이미지 ID와 SBOM 식별/해시를 고정한다. Testcontainers의 PostgreSQL 빌드 변형은 별도7번째 이미지로 기록한다. 검사 DB 생성 시각·스키마·해시와 도구 버전을 남기며 가능한 최신 공식 DB를 사용한다. 이미지/SBOM/소스는 외부로 업로드하지 않는다.
- 검출0으로 만들기 위한 ignore/VEX·권한 완화·인증/RLS/CSRF/TLS 비활성화는 하지 않는다. 공급자 수정판이 없는 항목은 근거·영향·남은 조건을 기록하고 해결 완료로 표시하지 않는다.
- 설정·전용 로컬 DB·공개 도구/DB 다운로드와 필요한 안전한 수정은 현재 사용자 요청과 기존 승인 범위다. 기본 데이터 삭제·전역 도구/신뢰 설정·외부 배포·Git Push는 이 실행에 포함하지 않는다.

## 작업과 검증 순서

1. [x] 기존 소스/검사 이력·현재 git status·기본 app/DB 식별자를 확인하고 원시 결과 경로 `target/react-soak-security-20261027/`를 준비한다.
2. [x] `scripts/test_http_mixed_soak.py`에서 React JSON·CSRF/명령·의미 검증의 실패를 먼저 확인하고 `scripts/http-mixed-soak.py`를 최소 변경한다. `scripts/test_multipart_http.py`와 `scripts/verify-multipart-http.py`도 같은 방식으로 현재 boot JSON과 대조군 계약을 검증한다.
3. [x] 전체 프로젝트 이미지의 SBOM/원시 검사를 실행하고 공식 자료로 수정 가능성과 잔여 항목을 분류한다. `scripts/security-inventory.py`의 기존 원장 형식을 재사용한다.
4. [x] 확인된 앱/도구 결함은 회귀 RED→수정→GREEN, 보안 패치는 공식 버전·라이선스·호환성과 해당 테스트로 확인한다. 필요한 이미지를 재빌드하고 수정 전후 같은 DB로 재검사한다.
5. [x] `Phase6HttpFixture`의 새 전용 PostgreSQL과 최종 Linux app을 loopback으로 연결하고 HTTP 보안·multipart fields/file·60초 혼합 smoke와 의미 검증을 통과시킨다.
6. [x] 실제33분 부하와 자원 관측을 실행한다. 문제 발견 시 원인을 수정하고 최종 소스/이미지에서 새 표본으로 다시 실행한다.
7. [x] 종료 DB 정합성·fixture 정상 종료·소유 임시 자원 정리·기본 DB/app 보존을 확인한다. 관련 Java/프론트/Python 전체 회귀 및 기본 HTTP를 재실행한다.
8. [x] `docs/quality/react-soak-security.md`에 최종 표본·고정 이미지/DB·발견/수정·잔여 취약점·실행하지 못한 범위를 기록하고 README/PRD/개발 안내에 연결한다. 새 파일/문서 링크·git diff/status를 확인한다.

주요 실행은 기존 전용 JDK/Maven과 `python -B`를 사용한다. `npm --prefix frontend test`·`npm --prefix frontend run build`, Maven `-Dfrontend.skip=true verify`, `python -B -m unittest discover -s scripts -p 'test_*.py'`를 코드 변경 범위와 최종 실행물에 맞게 실행한다. 전용 fixture의 실제 CLI 인자는 기존 코드 확인 후 원시 실행 기록에 그대로 남긴다.

진행 중 확인한 PostgreSQL OpenSSL 수정 공급을 반영해 공통 `infra/postgres/Dockerfile`과 Compose·Testcontainers를 연결했다. 첫7분 표본은 중단 이력으로 보존하고 최종 표본에 합산하지 않는다. 기본 환경은 부하 동안 보존하며, 종료 검증 후 백업·전체 업무 행 해시·볼륨/클러스터/RLS 대조를 거쳐 검증한 일반 이미지로 로컬 갱신한다. 테스트 자동 정리 라벨이 붙은 이미지와 일반 배포 이미지를 구분한다.
