# Claude 작업 안내

이 저장소의 공통 정책은 [AGENTS.md](AGENTS.md)를 단일 기준으로 사용한다. 이 파일에 공통 규칙을 중복해 정의하지 않는다.

작업을 시작할 때 다음 순서로 직접 읽고 준수한다.

1. [AGENTS.md](AGENTS.md): 공통 작업 방식, 검증, 사전 확인과 현재 단계 제한
2. [README.md](README.md): 프로젝트 개요와 현재 상태
3. [docs/kickoff.md](docs/kickoff.md): 프로젝트 시작 당시 배경과 원칙
4. [docs/prd.md](docs/prd.md): 현재 MVP·의사결정·시나리오 및 상세 규칙·설계 링크
5. 작업 대상의 `docs/rules/`, `docs/design/`, `docs/adr/` 문서: 입력·기대 결과·권한·인터페이스·기술 조건

현재는 사용자가 승인한 Phase 5의 조직·사원·계정 초대·역할·승인자 배정을 로컬에서 구현·검증한 단계다. 프로젝트·설정 생성, 전용 로컬 DB·Docker, 검증 후 단위별 커밋·일반 Push는 AGENTS.md의 범위에서 진행한다. 실행 계약은 `docs/design/account-access.md`, 계획은 `docs/superpowers/plans/2026-10-24-phase5-account-access.md`를 따른다. 첫 단위 밖의 근태 업무·외부 서버·배포는 범위에 포함하지 않는다.

문서 사이에 충돌이나 누락이 있으면 임의로 결정하지 말고 해당 내용을 보고한다. 사용자의 명시적 지시와 승인 범위를 우선하되, 이미 승인된 범위는 다시 묻지 않고 진행한다. 범위 밖 위험 작업은 AGENTS.md의 사전 확인 규칙을 따른다.
