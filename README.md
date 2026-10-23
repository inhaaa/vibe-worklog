# vibe-attendance

**AI-Assisted HR Attendance Management SaaS**

AI 바이브 코딩을 활용하여 설계하고 개발하는 중소기업 대상 멀티테넌트 근태관리 SaaS 프로젝트입니다.

- **Project Status:** Planning & Design
- **Started:** 2026-10-23
- **Development:** Personal Project
- **Internal Name:** `tna` (Time & Attendance)
- 기능 구현·개발 및 운영 환경 구축: 미착수

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

위 목록은 전체 제품 후보이며 구현 완료 목록이 아닙니다. 현재 MVP 방향은 고정 근무, 여러 고정 템플릿·사원별 적용일, 허용 IP 우선, 1단계 지정 승인, 종일 연차·기초 잔여 이력·승인 시 차감입니다. 자기 승인은 차단하며, 정상 지원은 당일 시작·종료하는 하루 한 근무 구간입니다. 반차·시간 단위 휴가·연차 자동 발생·QR은 후속 후보입니다. 조건부 기능과 세부 정책은 [PRD](docs/prd.md)를 따릅니다.

## Tech Stack (Planned)

| Category | Technology |
|---|---|
| Language | Java 25 (LTS) |
| Backend | Spring Boot 4.1.2 |
| Data Access | MyBatis 4.1.0 |
| Database | PostgreSQL 18 |
| Frontend | Thymeleaf, htmx, HTML/CSS/JavaScript |
| Security | Spring Security |
| Build | Maven, Maven Wrapper |
| Migration | Flyway |
| Testing | JUnit 5, Testcontainers |
| Infrastructure | Docker, Docker Compose, Caddy, Linux VPS |
| 기타 | Apache POI, Spring Boot Actuator |

위 버전은 도입 후보이며 출시·지원·호환성 검증 완료를 뜻하지 않습니다. 원격 README의 Spring Boot 4.1.2·MyBatis 4.1.0 후보를 보존했으며, kickoff의 4.1.x·4.x 계획과 함께 실제 생성 전에 공식 문서로 검증합니다.

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
- Business Rules: `docs/rules/` — 근태 계산 및 법령 기반 업무 규칙 작성 예정
- Architecture Decisions: `docs/adr/` — 주요 기술 의사결정 기록 예정
- System Design: `docs/design/` — 아키텍처, ERD 및 화면 설계 예정
- [AGENTS.md](AGENTS.md), [CLAUDE.md](CLAUDE.md): AI 작업 지침

## Roadmap

- [ ] 제품 요구사항 및 MVP 범위 확정
- [ ] 근태 업무 규칙 명세 및 테스트 케이스 작성
- [ ] 시스템 아키텍처 및 데이터베이스 설계
- [ ] 개발 환경 및 배포 파이프라인 구성
- [ ] 핵심 근태관리 기능 구현
- [ ] 보안 및 성능 테스트
- [ ] 파일럿 검증 및 서비스 개선

## Disclaimer

본 프로젝트는 개인이 독립적으로 진행하는 프로젝트입니다.

업무 규칙은 공개 법령 및 공식 자료를 기반으로 작성하며, 특정 기업의 내부 소스코드, 설계 문서 또는 고객 데이터를 사용하지 않습니다.

현재 기획·설계 단계이며 운영 서비스가 아닙니다. 기재된 기능과 기술 구성은 개발 과정에서 변경될 수 있습니다. 법령 세부 기준과 기술 호환성은 별도 공식 검증이 필요하며 문서는 법률 적합성을 보증하지 않습니다.
