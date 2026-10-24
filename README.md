# vibe-attendance

**AI-Assisted HR Attendance Management SaaS**

AI 바이브 코딩을 활용하여 설계하고 개발하는 중소기업 대상 멀티테넌트 근태관리 SaaS 프로젝트입니다.

- **Project Status:** Phase 5 · Fixed Schedule & MVP Development
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
- 일·주·월 집계·불변 검토용 Excel·감사 조회: 로컬 검증 완료
- 로컬 모의 비밀번호 재설정: 일회성 RESET·세션 회수 구현
- 운영자 별도 인증·신규 고객사·최초 초대 복구: 로컬 연결, 운영 환경·외부 배포 미착수

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
| Data Access | MyBatis Spring Boot Starter 4.1.0 / MyBatis 3.5.19 |
| Database | PostgreSQL 18.6 |
| Frontend | Thymeleaf 3.1.5.RELEASE, htmx 2.0.11, HTML/CSS/JavaScript |
| Security | Spring Security |
| Build | Maven 3.9.16, Maven Wrapper 3.3.4 |
| Migration | Flyway 12.4.0 (웹앱과 별도 실행) |
| Testing | Boot 관리 JUnit 6.0.3, Testcontainers |
| Infrastructure | Docker Compose 로컬 구성; Caddy·Linux VPS는 후속 |
| 기타 | Spring Boot Actuator; 검토용 XLSX는 JDK 최소 OOXML ([ADR-0002](docs/adr/0002-review-xlsx.md)) |

[ADR-0001](docs/adr/0001-technology-baseline.md)에 공식 기술 근거를 기록했습니다. 현재 Java 25 빌드·패키징과 실제 PostgreSQL Testcontainers 전체331개 테스트를 확인했습니다. 고정 일정·출퇴근·연차/정정 승인·집계·Excel·계정 복구·운영자 권한 경계를 포함합니다. 로컬 Docker·브라우저 검증과 남은 수용 조건은 [개발 안내](docs/development/local.md)를 따릅니다. 전이 의존성126개의 [라이선스 메타데이터](docs/development/dependency-licenses.csv)는 배포물 전체의 법률 감사 완료를 뜻하지 않습니다.

## Local Development

고정 근무의 핵심 흐름을 로컬에서 연결한 상태이며 전체 수용 조건 완료·운영 출시는 아닙니다. 로컬 환경의 준비·실행·테스트 명령은 [개발 안내](docs/development/local.md)를 따릅니다. 기본 주소는 `http://127.0.0.1:18080`이며 기본 사용자·공용 비밀번호는 제공하지 않습니다. 로그인·본인 준비 화면과 담당자의 조직·사원·소속·계정·역할·당일 승인자 관리 화면을 제공합니다. 초대는 `local` 프로필의 메모리 모의 전달만 지원하며 외부 메일을 발송하지 않습니다. 사원 등록으로 로그인 계정을 자동 생성하지 않으며 비밀번호 재설정은 같은 메모리 모의 전달을 사용하며 최초 고객사 온보딩은 별도 운영자 UI의 명시적 활성화와 독립 로컬 부트스트랩을 사용합니다.

Java 25에서 `mvnw.cmd test`는 단위 테스트를, `mvnw.cmd verify`는 DB 통합 테스트까지 실행합니다. 통합 테스트에는 Docker 또는 안내에 명시한 전용 로컬 테스트 PostgreSQL이 필요합니다. 기존 서비스의 DB·컨테이너를 재사용하지 않습니다.

## Architecture

Spring Boot 기반 모듈형 모놀리식 아키텍처를 채택합니다.

- 단일 애플리케이션과 데이터베이스를 통한 운영 복잡도 최소화
- `tenant_id`와 PostgreSQL RLS를 활용한 고객사별 데이터 격리
- 근태 업무 영역별 모듈 분리
- 원천 출퇴근 기록과 근로시간 계산 결과의 분리
- 규칙 기반 근태 계산 및 자동화 테스트
- Docker 기반 배포 및 백업 체계

단일 애플리케이션이 단일 서버의 처리량을 보장하지는 않습니다. PC·모바일 반응형 웹을 지향하고 수용 가능한 부하는 후속 테스트로 검증합니다.

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
- [ ] 계산 입력·정책 상세 화면과 감사 상세·페이지 탐색 수용 조건 보완
- [ ] 보안 및 성능 테스트
- [ ] 파일럿 검증 및 서비스 개선

## Disclaimer

본 프로젝트는 개인이 독립적으로 진행하는 프로젝트입니다.

업무 규칙은 공개 법령 및 공식 자료를 기반으로 작성하며, 특정 기업의 내부 소스코드, 설계 문서 또는 고객 데이터를 사용하지 않습니다.

현재 로컬 MVP 개발 단계이며 운영 서비스가 아닙니다. 조직·사원·계정·일정·출퇴근·종일 연차·신청 승인·검토 집계를 로컬에서 연결했습니다. 기재된 기능과 기술 구성은 개발 과정에서 변경될 수 있습니다. 공개 자료 조회·로컬 구현 테스트와 실제 고객 적용·운영 검증은 구분합니다. 법적 예외·개인정보 처리·운영 보안·부하·백업 복원 등 남은 검증이 있으며, 문서는 법률 적합성을 보증하지 않습니다.
