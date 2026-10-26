# vibe-attendance

**AI-Assisted HR Attendance Management SaaS**

AI 바이브 코딩을 활용하여 설계하고 개발하는 중소기업 대상 멀티테넌트 근태관리 SaaS 프로젝트입니다.

- **Project Status:** Phase 6 · Local Quality Validation
- **Started:** 2026-10-23
- **Development:** Personal Project
- **Internal Name:** `tna` (Time & Attendance)
- 기획·지원 범위 업무 규칙·시스템 설계 문서: 작성
- 로컬 개발 기반: 빌드·DB 격리·로그인·준비 화면 구현 및 native PostgreSQL 검증
- Docker: Linux 이미지 빌드·Compose 기동·Testcontainers 검증 완료
- 조직·사원·적용일별 소속 관리: 첫 구현 단위 및 로컬 검증 완료
- 계정 초대·수락·역할·당일 승인자 배정: 로컬 모의 전달 구현 및 검증 완료
- 고정 근무 템플릿·불변 버전·사원별 적용일 배정: 구현 및 PostgreSQL 검증 완료
- CALC-1 시간 계산: 일·주·월 결과, 미확정·오래됨 표시와 수동 재집계 연결
- 허용 IP·휴일 캘린더·원천 출퇴근·퇴근 후 실제 근로 확인: 구현 및 로컬 DB 검증 완료
- 종일 연차·기초 원장·독립 승인·취소·정정: 구현 및 로컬 검증 완료
- 일·주·월 집계·계산 근거 상세·불변 검토용 Excel·안전한 감사 상세/페이지: 로컬 검증 완료
- 로컬 모의 비밀번호 재설정: 일회성 RESET·세션 회수 구현
- 운영자 별도 인증·신규 고객사·최초 초대 복구: 로컬 연결, 운영 환경·외부 배포 미착수
- HTTP 혼합33분·전체 의존성/이미지 검사: 로컬 실행 완료, 보안 패치와 미해결 취약점 기록
- 이전 Linux 이미지 부하: multipart·HTTP 보안·33분 15,471요청 오류0·p95 241.014ms (`d58a893`)
- 현재 로컬 데모: 회사코드 없이 `admin / admin`, Java433개·Python35개 통과, 최종 이미지4종 보안 재검사·Ryuk loopback/정리 확인

## Overview

국내 중소기업의 근태관리 업무를 효율적으로 처리하기 위한 웹 기반 SaaS입니다.

출퇴근 기록부터 근태 신청 및 승인, 연차 관리, 근로시간 집계까지 하나의 시스템에서 관리할 수 있도록 설계합니다.

고객사별 별도 개발 없이 동일한 애플리케이션을 제공하는 멀티테넌트 아키텍처를 지향하며, 국내 근로기준법과 관련 법령을 고려한 근태 관리 기능을 구현하는 것이 목표입니다.

주요 고객은 상시근로자 10~300명 국내 사업장이며 최소 지원 규모는 5인 이상입니다. 이 제품 범위가 개별 법령의 일괄 적용 기준은 아닙니다. 고객별 설정을 허용하고 개별 코드 커스터마이징은 지양합니다.

## Key Features (Planned)

- **Attendance Tracking:** 출퇴근 기록, 근태 현황, 기록 정정
- **Leave Management:** 연차 발생, 휴가 신청, 잔여 연차 관리
- **Approval Workflow:** 근태 신청 및 승인 프로세스
- **Work Schedule:** 근무유형 설정 및 근무 스케줄 관리
- **Working Hours:** 근로시간 자동 집계, 연장 및 휴일근무 관리
- **HR Dashboard:** 조직별 근태 현황과 월별 집계
- **Multi-Tenancy:** 고객사별 데이터 격리 및 권한 관리
- **Mobile Support:** 반응형 웹과 PWA 지원

위 목록은 전체 제품 후보이며 구현 완료 목록이 아닙니다. 현재 MVP 방향은 고정 근무, 여러 고정 템플릿·사원별 적용일, 허용 IP 우선, 1단계 지정 승인, 종일 연차·기초 잔여 이력·승인 시 차감입니다. 자기 승인은 차단하며, 정상 지원은 당일 시작·종료하는 하루 한 근무 구간입니다. 일반 검토용 Excel 다운로드를 MVP에 포함합니다. 반차·시간 단위 휴가·자동 발생·QR·추가근무 신청·월 마감·PWA는 후속입니다. 상세 범위와 정책은 [PRD](docs/prd.md)를 따릅니다.

## Tech Stack

| Category | Technology |
|---|---|
| Language | Java 25 (Eclipse Temurin 25.0.4.1+1) |
| Backend | Spring Boot 4.1.2 |
| Data Access | MyBatis Spring Boot Starter 4.1.0 / MyBatis 3.6.0 (보안 패치·저장소 회귀 검증) |
| Database | PostgreSQL 18.6 |
| Frontend | Thymeleaf 3.1.5.RELEASE, htmx 2.0.11, HTML/CSS/JavaScript |
| Security | Spring Security |
| Build | Maven 3.9.16, Maven Wrapper 3.3.4 |
| Migration | Flyway 12.4.0 (웹앱과 별도 실행) |
| Testing | Boot 관리 JUnit 6.0.3, Testcontainers |
| Infrastructure | Docker Compose 로컬 구성; Caddy·Linux VPS는 후속 |
| 기타 | Spring Boot Actuator; 검토용 XLSX는 JDK 최소 OOXML ([ADR-0002](docs/adr/0002-review-xlsx.md)) |

[ADR-0001](docs/adr/0001-technology-baseline.md)에 공식 기술 근거를 기록했습니다. 최신 Java 25 빌드·패키징과 실제 PostgreSQL 통합을 포함한 전체433개(단위65·통합368), Python 도구35개 테스트를 확인했습니다. 고정 일정·출퇴근·연차/정정 승인·집계·Excel·계정 복구·운영자 권한 경계를 포함합니다. 현재 데모·이미지 보안·Ryuk 검증은 [최종 보안 재검사](docs/quality/security-runtime-recheck.md), 이전 Linux 이미지의 HTTP·지속 부하는 [최종 Linux 재검증](docs/quality/runtime-image-soak.md), 이전 업무 연결·격리·동시성·논리 복원은 [기존 품질 보고서](docs/quality/phase6-report.md), 전체 의존성·이미지 보안 패치와 잔여 취약점은 [후속 검사 보고서](docs/quality/http-soak-vulnerability.md), 실행 방법은 [개발 안내](docs/development/local.md)를 따릅니다. 전이 의존성126개의 [라이선스 메타데이터](docs/development/dependency-licenses.csv)는 배포물 전체의 법률 감사 완료를 뜻하지 않습니다.

Phase 6에서는 권한 회수 중 조회·300명 사원 선택 누락·월 조회 직렬화를 수정했습니다. 로컬 합성 부하498개는 오류0이며 동일 고객사 월 조회 p95는2.29→0.85초였습니다. 운영 용량·SLA를 확정하는 결과는 아닙니다.

2026-10-25 당시 Windows native 서버의 후속 HTTP 혼합 부하는18세션·33분·15,471요청에서 오류0, p95 619ms였고32분 steady는8요청/초였습니다. 전체 의존성·빌드 도구와 당시 이미지4종을 로컬 검사해 MyBatis 내부 OGNL·플러그인을 패치하고 migrate 이미지를 줄였습니다. 앱/migrate의 Java archive 검출은0이나 OS·개발 도구의 미해결 항목은 [후속 보고서와 원장](docs/quality/http-soak-vulnerability.md)에 남겼습니다. 이 측정은 아래 최종 Linux 이미지의 부하 성적과 구분합니다.

잔여 OS·개발 도구의 실제 영향과 대응 순서는 [우선순위 보고서](docs/quality/residual-vulnerability-impact.md)를 따릅니다. Noble zlib 패치·마이그레이션 권한 제한·DB의 비루트 시작과 Java 기본 신뢰 저장소의 공식 CA 임시 대응을 검증했습니다. 해당 보완 당시 Linux396개·신뢰 저장소6개·HTTP24개를 통과했습니다. 공급 대기 JDK·libxml2 등 배포 전 조건과 scanner 불일치는 별도로 남깁니다.

2026-10-25 후속 [공식 공급 재확인](docs/quality/security-supply-recheck.md)에서 Windows/Noble JDK와 bookworm libxml2의 공급 대기를 확인했습니다. 새 gosu1.20은 별도 사용자 파일 파싱 수정의 적용 후보이며 기존 Go stdlib 경고 해결판은 아닙니다. OS·개발 도구를 적용 가능 후보·공급 대기·추가 분석으로 분류했으며 이 공급 확인 단위에서는 설치·설정·DB·컨테이너 변경이나 회귀/부하 재실행을 하지 않았습니다.

이후 [gosu 서명·바이너리 분석](docs/quality/gosu-binary-analysis.md)에서 정상 서명2건·변조 거부3건과 고정 DB의1.19/1.20 차이를 확인했습니다. 기존 Medium1건의 취약 심볼 경고는1.20에서 제거됐으며 C/H35건은 심볼 미보고를 무영향 확정으로 바꾸지 않았습니다. 현재 비루트 Compose를 유지하고 gosu 전용 파생 이미지는 도입하지 않으며, 기본 Testcontainers의 별도 root 시작 경로와 공식 이미지 갱신은 추적합니다.

[native 입력·Docker 경계 분석](docs/quality/native-input-docker-boundaries.md)에서는 PostgreSQL ELF943개를 독립 대조하고 실제 JRE의 DNS/libc 경로·선택 라이브러리 의존성을 확인했습니다. 설정을 저장하지 않는 로컬 진단에서 named pipe·classic HTTP1 ping/info를 확인했으며 DockerJava의 P2와 libxml2의 P1은 유지합니다. 분석 중 발견한 미지원 multipart 자동설정은 [후속 보완](docs/superpowers/plans/2026-10-25-multipart-boundary.md)과 아래 이미지 재검증으로 이어졌습니다.

2026-10-26 multipart 설정 보완 단위에서는 업로드 미지원 계약에 맞춰 자동설정을 비활성화했습니다. 기본·local 실제 ConfigData에서 파서/Servlet 설정 bean의 부재를 검증했고, 새 전용 로컬 PostgreSQL에서 당시 전체398개(단위48·통합350)를 통과했습니다. 이 단위에서는 Compose 이미지 재생성·실제 HTTP·부하를 수행하지 않았으며 [당시 결과](docs/development/local.md#미지원-multipart-자동설정-비활성화-2026-10-26)로 보존합니다.

이후 [최종 Linux 이미지 재검증](docs/quality/runtime-image-soak.md)에서 전체 Java402개(단위52·통합350)·Python35개 회귀를 통과하고 새 app/migrate 이미지를 빌드해 기본 app에 반영했습니다. 기본 DB는 보존했으며 새 이미지의 app은 running, migrate는 V10 no-op·종료0입니다. 별도 loopback 합성 DB와 Linux app에서 실제 HTTP24개·multipart12개를 통과했습니다. 최종33분은1,980.205초·15,471요청·예상 밖 오류/논리 실패0·p95 241.014ms이며 종료 정합성·기본 DB 보존과 기본 HTTP24개 재검증도 통과했습니다. 이 단위에는 최종 이미지의 새 SBOM/취약점 재검사와 Ryuk 게시 경계 후속 확인이 포함되지 않습니다.

2026-10-26 후속으로 [로컬 데모 로그인](docs/design/local-demo-login.md)을 적용하고 전체 Java433개·Python35개, 실제 데모 HTTP16개·공통 HTTP24개를 통과했습니다. 최종 이미지4종은 새 고정 Grype DB로 재검사해 원시 매치1,289행을 기록했습니다. 새 CVE3개/5행은 Ubuntu 평가 대기이며 기존 JDK·libxml2 등 미해결 항목은 유지합니다. Ryuk는 실제 loopback 단일 게시·대상/익명 볼륨 자동 정리·다른 자원 보존을 확인했습니다. privileged·root·Docker 소켓 RW 권한은 남습니다. 상세 근거는 [현재 보고서](docs/quality/security-runtime-recheck.md)를 따르며 이번 로그인 이미지로 33분 부하를 재측정하지 않았습니다.

## Local Development

고정 근무의 핵심 흐름과 계산·감사 상세·서울 시각 표시를 로컬에서 검증한 상태이며 운영 서비스는 아닙니다. 로컬 환경의 준비·실행·테스트 명령은 [개발 안내](docs/development/local.md)를 따릅니다. 기본 주소는 `http://127.0.0.1:18080`입니다. 현재 Compose의 `local,local-demo`에서는 회사코드 없이 아이디 `admin` / 비밀번호 `admin`으로 로그인합니다. 합성 로컬 데모 전용이며 명시 CLI로 자료를 준비합니다. 일반 프로필은 회사코드·이메일 인증과 기존 비밀번호 정책을 유지합니다. 로그인·본인 준비 화면과 담당자의 조직·사원·소속·계정·역할·당일 승인자 관리 화면을 제공합니다. 초대는 `local` 프로필의 메모리 모의 전달만 지원하며 외부 메일을 발송하지 않습니다. 사원 등록으로 로그인 계정을 자동 생성하지 않으며 비밀번호 재설정은 같은 메모리 모의 전달을 사용하며 최초 고객사 온보딩은 별도 운영자 UI의 명시적 활성화와 독립 로컬 부트스트랩을 사용합니다.

Java 25에서 `mvnw.cmd test`는 단위 테스트를, `mvnw.cmd verify`는 DB 통합 테스트까지 실행합니다. 통합 테스트에는 Docker 또는 안내에 명시한 전용 로컬 테스트 PostgreSQL이 필요합니다. 기존 서비스의 DB·컨테이너를 재사용하지 않습니다.

## Architecture

Spring Boot 기반 모듈형 모놀리식 아키텍처를 채택합니다.

- 단일 애플리케이션과 데이터베이스를 통한 운영 복잡도 최소화
- `tenant_id`와 PostgreSQL RLS를 활용한 고객사별 데이터 격리
- 근태 업무 영역별 모듈 분리
- 원천 출퇴근 기록과 근로시간 계산 결과의 분리
- 규칙 기반 근태 계산 및 자동화 테스트
- Docker 기반 배포 및 백업 체계

단일 애플리케이션이 단일 서버의 처리량을 보장하지는 않습니다. PC·모바일 반응형 웹과 로컬 합성 부하는 검증했으며 운영 환경의 수용량·SLA는 후속 테스트로 검증합니다.

## AI-Assisted Development

본 프로젝트는 Claude Code, Codex 등 AI 개발 도구를 활용하는 Vibe Coding 프로젝트입니다.

AI를 요구사항 정리, 설계 검토, 코드 작성, 테스트 생성 및 리팩터링에 활용하며, 최종 설계와 구현 결과는 개발자가 검토합니다.

특히 다음 원칙을 준수합니다.

- AI 생성 코드에 대한 변경사항 검토
- 근태 계산 규칙의 테스트 기반 검증
- 데이터베이스 변경 및 주요 설계 결정의 검토
- 공개 자료를 기반으로 한 독립적인 업무 규칙 구현
- 특정 회사의 소스코드, 내부 문서 및 실제 업무 데이터 사용 금지

AI 활용은 개발 지원 방식이며 서비스에 AI 기능이 탑재된다는 뜻이 아닙니다. SDD로 스키마·인터페이스를 먼저 정의하고, TDD로 테스트 작성 → 구현 → 리팩토링을 진행합니다.

## Documentation

- [Project Kickoff](docs/kickoff.md): 프로젝트 방향 및 초기 기술 설계
- [Product Requirements](docs/prd.md): 제품 요구사항과 MVP 결정 기록
- [Market Research](docs/research/market.md): 공식 제품 비교·문제 가설·파일럿 검증 계획
- Business Rules: [신청·승인·정정](docs/rules/workflow.md), [계산·법령 근거·검증 사례](docs/rules/calculation.md)
- System Design: [아키텍처](docs/design/architecture.md), [논리 ERD](docs/design/data-model.md), [보안](docs/design/security.md), [화면·HTTP 계약](docs/design/screens.md)
- [Leave Workflow](docs/design/leave-workflow.md): 종일 원장·독립 승인·취소·정정 효과
- [Attendance Recording](docs/design/attendance-recording.md): 허용망·휴일·원천·사실/검토 개정·본인 확인
- [Worktime Results](docs/design/worktime-results.md): 일·주·월 결과·정책 근거·불변 검토용 Excel
- [Worktime Engine](docs/design/worktime-engine.md): CALC-1 순수 산정과 미확정·부분합 계약
- [Fixed Schedule](docs/design/fixed-schedule.md): 고정 템플릿·불변 버전·사원별 적용일 배정
- [Organization Management](docs/design/org-management.md): Phase 5 첫 조직·사원·소속 관리 계약과 범위
- [Operator Onboarding](docs/design/operator-onboarding.md): 독립 인증·신규 고객사·최초 초대 복구
- [Password Reset](docs/design/password-reset.md): 목적·만료·일회성·세션 회수와 모의 전달
- [Account Access](docs/design/account-access.md): 초대·역할·당일 승인자 배정 계약
- [Architecture Decision 0001](docs/adr/0001-technology-baseline.md): 기술 기준선·공식 검증 근거·구현 전 조건
- [Local Development](docs/development/local.md): 로컬 실행·마이그레이션·테스트·남은 검증
- [HTTP & Vulnerability Validation](docs/quality/http-soak-vulnerability.md): 33분 부하·전체 검사·보안 패치·미해결 항목
- [Security Fix Availability](docs/quality/security-supply-recheck.md): 공식 수정판 공급 재확인·적용 후보·공급 대기·추가 분석
- [gosu Binary Analysis](docs/quality/gosu-binary-analysis.md): 서명·제외 없는 바이너리 검사·61행 대조·파생 이미지 판단
- [Native & Docker Boundaries](docs/quality/native-input-docker-boundaries.md): native 입력·ELF 교차 대조·읽기 전용 Docker 연결 진단
- [Final Linux Image Revalidation](docs/quality/runtime-image-soak.md): multipart 설정 반영 당시 이미지·실제 HTTP·혼합 부하 재검증
- [Current Security Recheck](docs/quality/security-runtime-recheck.md): 데모 적용 이미지4종·신규 원시 원장·Ryuk 게시/정리 경계
- [Local Demo Login](docs/design/local-demo-login.md): 회사코드 없는 로컬 `admin / admin` 계약
- [Third-party Notices](THIRD_PARTY_NOTICES.md): 포함 자산 고지와 의존성 조사 범위
- [AGENTS.md](AGENTS.md), [CLAUDE.md](CLAUDE.md): AI 작업 지침

## Roadmap

- [x] 제품 요구사항·MVP 범위·역할·핵심 시나리오 문서화
- [x] 지원 범위의 업무 규칙·테스트 사례 명세 및 미검증 항목 구분
- [x] 아키텍처·논리 데이터 모델·보안·화면·인터페이스 설계
- [x] 빌드·native PostgreSQL·테넌트 격리·로그인·준비 화면 기반 검증
- [x] 로컬 Docker Compose 구성·설정 검증
- [x] Docker 이미지 빌드·Compose 기동·Testcontainers 실행 검증
- [x] 조직 계층·사원 재직·적용일별 소속 관리 첫 단위 검증
- [x] 로컬 모의 계정 초대·수락·역할·당일 승인자 배정 검증
- [x] 고정 근무 템플릿·사원별 적용일 배정
- [ ] 운영 환경·배포 파이프라인 구성
- [x] 고정 근무 핵심 업무 흐름의 로컬 연결·회귀 검증
- [x] 계산 당시 입력·정책·실행 근거 상세와 현재성·버전 구분
- [x] 안전한 감사 전후 상세·추적 ID·양방향 커서 페이지 탐색
- [x] 기존 화면·새 검토용 출력의 서울 시각·밀리초 표시 통일
- [x] 로컬 MVP 시나리오·격리·동시성·보안·성능·논리 복원 검증
- [x] 로컬 HTTP 혼합33분·전체 해석 의존성/도구·이미지 취약점 검사와 가용 패치
- [x] 잔여 취약점 영향·공식 수정판 공급 재확인과 후속 분류
- [x] multipart 반영 최종 Linux 이미지·실제 HTTP·33분 혼합 부하·종료 정합성 재검증
- [x] 최종 이미지4종 SBOM/취약점 재검사·Ryuk loopback 게시/정리 경계 확인
- [x] 회사코드 없는 합성 로컬 데모 `admin / admin`과 일반 인증 회귀 검증
- [ ] 미해결 P1 보안 항목 대응·운영 사양의 장기/장애 부하·침투·오프사이트 복구
- [ ] 파일럿 검증 및 서비스 개선

## Disclaimer

본 프로젝트는 개인이 독립적으로 진행하는 프로젝트입니다.

업무 규칙은 공개 법령 및 공식 자료를 기반으로 작성하며, 특정 기업의 내부 소스코드, 설계 문서 또는 고객 데이터를 사용하지 않습니다.

현재 로컬 MVP 품질 검증 단계이며 운영 서비스가 아닙니다. 조직·사원·계정·일정·출퇴근·종일 연차·신청 승인·검토 집계를 로컬에서 연결했습니다. 기재된 기능과 기술 구성은 개발 과정에서 변경될 수 있습니다. 공개 자료 조회·로컬 구현 테스트와 실제 고객 적용·운영 검증은 구분합니다. 법적 예외·개인정보 처리·미해결 취약점·운영 사양의 장기 부하·오프사이트 복원 등 남은 검증이 있으며, 문서는 법률 적합성을 보증하지 않습니다.
