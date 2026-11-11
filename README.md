# WorkLog

**AI-Assisted HR Attendance Management SaaS**

AI 바이브 코딩을 활용하여 설계하고 개발하는 중소기업 대상 멀티테넌트 근태관리 SaaS 프로젝트입니다.

아래 테스트 수치는 각 작업·브랜치의 실행 당시 결과입니다. 2026-11-11 기준 [WorkLog 공개 데모](https://worklog.inhaaa.com)는 `main`의 문서형 화면·업무 화면과 기존 데모 보안을 통합한 `58f8159`·V17을 실행합니다.

- **Project Status:** Phase 6 · Local Quality Validation
- 문서형 화면·업무 화면 공개 반영 (2026-11-11). `a65111c`까지 추가 26개 완료 커밋과 예정 시간 정밀도·대상 사원 표시 수정을 `58f8159`·V17에 반영했습니다. 일반 프런트 511개·공개 프런트 521개/타입/빌드, 공개 Java 799개와 공개 GET 109개·보안 34개·모달 조회 19개를 통과했습니다. 기존 자료·50개 컨테이너·Caddy와 실제 PC의 달력 요약·블루/차콜 신청서를 확인했습니다. 모바일과 계산 근거 모달 1개는 미검증입니다. [통합 검증](docs/quality/design-followup-20261111.md) · [실제 배포 결과와 한계](docs/quality/public-design-followup-deploy-20261111.md)
- 공통 디자인 시스템 공개 반영 (2026-11-11). `cc796c5`·V17에 Pretendard·공통 시트·저장 확인창을 반영했습니다. 공개 후보 Java 799개·프런트 496개·Python 74개와 공개 GET 109개·보안 HTTP 34개·모달 조회 19개를 통과했고, 기존 자료·49개 컨테이너·Caddy와 실제 PC의 두 테마를 확인했습니다. 모바일과 계산 근거 모달 1개는 미검증입니다. [실제 배포 결과와 한계](docs/quality/public-design-system-deploy-20261111.md)
- 공통 디자인 시스템 통합 검증 (2026-11-11). Claude 작업 25개를 개발본에 옮기고 저장 중 사유 변경으로 재시도 명령이 바뀌는 문제를 수정했습니다. Java 728개·프런트 486개·Python 67개, 타입·빌드와 두 로컬 포트에서 HTTP 24개씩·실제 두 테마 화면을 확인했습니다. 당시에는 공개 배포를 수행하지 않았습니다. [통합 범위와 검증 결과](docs/quality/claude-main-integration-20261111.md)
- 근태 캘린더와 신청서 공개 반영 (2026-11-10). 달력의 승인 연차만 색으로 표시하고, 결재선·신청자·입력란·요약을 한 장의 신청서에 배치했습니다. 공개 블루·차콜에서 날짜 메뉴로 신청서를 열어 연차와 정정의 대상일을 확인했습니다. [실제 배포 결과](docs/development/worklog-public-updates.md#2026-11-10-근태-캘린더와-문서형-신청서)
- 신청 기간 버튼 폭 수정 (2026-11-09). 공개 데모의 블루·차콜에서 달력을 열어도 시작일과 종료일 버튼이 같은 너비로 한 줄을 채웁니다. WorkLog 앱만 교체했으며 DB 자료와 기존 서비스 상태가 유지됐습니다. [실제 배포 결과](docs/development/worklog-public-updates.md#2026-11-09-신청-기간-버튼-폭-수정)
- 공개 데모 표기 통일 (2026-11-09, `e07594f`). 상단과 사이드바의 이름을 **WorkLog 데모**로 맞췄습니다. [화면 계약과 배포 검증](docs/design/worklog-landing.md)
- 데모 바로 입장·사번 변경 공개 반영 (2026-11-09, `153470d`). **WorkLog 시작하기**로 기본 관리자 `99999`에 입장한 뒤, 상단 **사번 변경**에서 승인자 전환과 관리자 복귀를 공개 PC·모바일에서 확인했습니다. 공개 프런트 테스트 430개와 배포 후 GET 검증 14개를 통과했습니다. [화면 계약과 검증](docs/design/worklog-landing.md)
- 신청 기간 시트 공개 반영 (2026-11-09). 공개 데모의 블루·차콜에서 시작일·종료일 선택, 범위 제한, 신청 요약 반영과 초점 복귀를 확인했습니다. WorkLog 앱 교체 전후 DB와 기존 서비스 상태를 대조했습니다. [실제 배포 결과](docs/development/worklog-public-updates.md#2026-11-09-신청-기간-시트-공개-반영)
- main 공개 반영 (2026-11-07). Hostinger 콘솔에서 WorkLog 앱을 교체했습니다. 기존 서비스와 DB 보존, 공개 HTTP 34개 및 모달 조회 19개, 블루·차콜의 인사 헤더·근태 신청·위젯 설정 모달을 확인했습니다. [실제 배포 결과와 검증 범위](docs/development/worklog-public-updates.md#2026-11-07-main-공개-반영)
- 업무 화면 재설계 (2026-11-06~07, 개발본). 계정별 홈 위젯 설정 모달, 항목별 아이콘과 선택 표시를 더한 인사 탭, 연차와 신청 기간 시트를 적용했습니다. 11월6일 프런트엔드 338개, 11월7일 인사 헤더 배색 조정 후 339개·타입·빌드와 블루·차콜 합성 화면 검증을 통과했습니다. 공개 배포는 별도입니다. [화면 계약과 검증](docs/design/modern-hr-workspace.md)
- 홈과 업무 화면 개편 (2026-11-06, 개발본). 로그인 후 홈, 업무 화면의 공통 인사 헤더, 신청 모달의 결재선과 신청서를 적용했습니다. React 327개, Java 141개, Python 62개와 차콜·블루의 합성 화면 검증을 통과했습니다. PostgreSQL 통합과 공개 서버 검증은 해당 실행 범위에 포함하지 않았으며 공개에 반영하지 않았습니다. [화면 계약과 검증](docs/design/workspace-home-refresh.md)
- 간결한 표 디자인: 2026-11-06 개발 `a859ccc`·공개 후보 `91d6c56`에 중립 라벨·가는 가로선·밑줄 탭·단순한 모달을 적용했습니다. 개발321개·공개404개/타입·빌드, 두 테마·모바일 합성 검증을 통과했습니다. 이 후보는 배포 준비까지 진행했습니다. [당시 검증 결과](docs/quality/quiet-records-20261106.md)
- 두 테마·정보표·공통 모달: 2026-11-06 공개 `40816d1`·V17에 차콜/블루 전환·색상 영역·내 정보7탭·업무 표·공통 모달과 계산 근거/감사 상세 정보표까지 반영했습니다. 공개 HTTP28항목·모달 GET17개, 실제 PC의 두 테마와 동일 공개 자산의 합성12조합을 확인했습니다. 기존48표446행·스키마·권한·Caddy·다른 서비스는 보존했고 감사 화면 조회 로그2행만 추가됐습니다. 실제 공개 모바일과 저장 결과가 없는 계산 상세는 검증 한계로 구분합니다 ([실제 결과와 한계](docs/quality/workspace-surfaces-20261105.md)).
- UI·모달 후속: 2026-11-05 공개 `d320dfc`·V17에 내정보7탭·사원 헤더·신청/결재 디자인과 배경을 보존하는 업무 모달을 반영했습니다. 개발312개·공개396개/타입·빌드, 공개HTTP26항목·PC1440px/모바일390px를 확인했습니다. 앱만 교체하고 기존48표446행·40컨테이너/Caddy/다른 서비스를 보존했습니다. 실제 업무 저장·신청 POST는 수행하지 않았습니다 ([최종 결과와 한계](docs/quality/workspace-modal-polish-20261105.md)).
- 상단 권한·사원 전환: 2026-11-04 공개 `b074d42`·V17에 반영했습니다. 펼친 권한 목록의 테마와 로그아웃 왼쪽 아이콘, HTTP 15개 및 기존 자료 보존을 확인했습니다. [공개 반영 결과](docs/quality/public-toolbar-deploy-20261104.md)
- 사번·권한·내정보 후속: 2026-11-03 공개 `aab37e6`·V17을 반영했습니다. 담당자 `99999`/이름 `담당자`, 입사연도별5자리 사번과 테스트5계정, 사번 로그인·보유 권한 메뉴·내정보7탭을 연결했습니다. 공개 Java793·프론트369/타입·빌드, HTTP98/60/220·사진4·자산·PC/390px와 백업/독립 복원·434→446행의 정확12행 추가·기존 서비스 보존을 확인했습니다 ([실제 공개 결과](docs/quality/public-identity-personnel-deploy-20261103.md)·[개발 결과와 한계](docs/quality/identity-personnel-20261103.md)).
- main 통합 후속 (2026-11-03): 기존 공통 시트·셀 편집과 인사정보·신청·결재 화면을 함께 통합했습니다. 화면346개/타입·빌드·Python67개, Java단위132개와 DB전체566개를 실행했고 동시성1건의 최초 실패 후 원래 조건의 최종2개 재검증을 통과했습니다. 첫 실패/진단/재발 가능성은 [main 통합 검증](docs/quality/main-approval-integration-20261103.md)에 구분했습니다.
- 결재 화면·데모 승인 후속: 2026-11-03 공개 `3e6e99c`·V16에 대기/완료 카드·승인/반려 검토와 별도 승인자 계정 전환을 반영했습니다. 개발 Java698·프론트262, 공개 Java763·프론트346/타입·빌드·Python74, 공개98+4/새 persona60항목과 실제 신청→독립 승인·12→12→11DAY·PC/390px를 확인했습니다 ([실제 공개 결과](docs/quality/public-approval-workspace-deploy-20261103.md)·[로컬 결과/한계](docs/quality/approval-workspace-20261103.md)).
- 신청 화면 후속: 2026-11-03 공개 `8cc811c`·V16에 현재 헤더·메뉴와 같은 인디고 카드·단계별 작성/요약·접수/상세를 반영했습니다. 개발251개·공개336개/타입·빌드, 합성 환경의 실제 접수·무쓰기 미리보기, 공개98+4항목/사진4·자산·PC/390px와 기존46표420행/권한·다른 서비스 보존을 확인했습니다 ([최종 공개 결과](docs/quality/public-request-workspace-deploy-20261103.md)·[로컬 검증/한계](docs/quality/request-workspace-20261103.md)).
- 가상 인사/사진·헤더 후속: 2026-11-02 공개 `725373d`에 가상4명 사진과 직급/직책/직무·재직/휴직/정직을 채웠습니다. 헤더 탭을 제거하고 전체 항목을 한 카드에 표시하며 3단계 메뉴 디자인을 보완했습니다. 공개 Java728·프론트323·Python74·98+4항목/사진4·PC/390px·백업/독립 복원·기존 자료/서비스 보존을 확인했고, 개발본 UI `edafb4c`도 관련62개·타입/빌드를 통과했습니다 ([개발/공개 결과](docs/quality/demo-personnel-header-20261102.md)).
- 공개 업무 메뉴·인사정보 반영: 2026-11-02 공개 `70a4e81`·V16 배포를 마쳤습니다. 백업/독립 복원·기존43표407행/권한·공개98+4항목·PC/390px·기존 서비스/Caddy를 검증했고 모바일 표시2건도 수정했습니다 ([최종 공개 결과](https://github.com/inhaaa/vibe-worklog/blob/codex/personnel-public-release/docs/quality/public-personnel-deploy-20261102.md)). 개발본에도 표시 보완을 반영하고 관련42개·타입/빌드를 확인했습니다.
- 업무 메뉴·인사정보 개편: 업무별3단계 메뉴·홈 제거/출퇴근 이전·공통 사원 헤더·실제 인사정보/사진/상태 이력 등록·단계형 신청 화면을 구현했습니다. Java671개·React237개·Python62개·타입/빌드·실제 HTTP54요청50검증·합성 복원을 통과했습니다. 최신 공개 시트와의 보안 통합·V16 공개 반영은 별도이며 이번 로컬 결과에 포함하지 않습니다 ([구현 계약](docs/design/personnel-workspace.md)·[검증 결과](docs/quality/personnel-workspace.md)).
- 공개 가상 데이터 데모: [worklog.inhaaa.com](https://worklog.inhaaa.com) · 이후 검증된 메뉴 단위는 공개 보안을 통합해 WorkLog 전용 앱·DB에 반영합니다 ([후속 배포 절차](docs/development/worklog-public-updates.md)). 개발본과 공개본의 버전은 다르며 기존 PriceSys·demo 보존과 배포 전후 검증을 포함합니다.

- 공통 업무 그리드: `WorklogSheet`의 상시 입력 박스를 없애고 클릭·F2 편집, Tab/Enter 이동, Esc 복원으로 개선했습니다. 조직·사원·결재선·공통/근태코드 적용과 원본246개 검증은 [셀 직접 편집 보고서](docs/quality/sheet-cell-editing.md)를 따릅니다.
- 시트 정렬·한 줄 표시: 헤더 중앙, 수량 우측·짧은 값 중앙·문장/4어절 이상 좌측과40px 한 줄 행을 적용했습니다. 일반251개·공개252개/타입·빌드 검증 후 2026-11-02 공개 `3529a25`에 배포했습니다. 공개72+4항목·정적8자산·PC/390px·기존 서비스/DB43표407행 보존을 확인했습니다 ([구현 검증](docs/quality/sheet-alignment.md)·[실제 배포 결과](docs/quality/public-sheet-alignment-deploy-20261102.md)).
- 공개 데모 최초 시트 반영 이력: [worklog.inhaaa.com](https://worklog.inhaaa.com) · 2026-10-29 통합 소스 `12f0f78`·V14와 클릭 편집 시트를 반영했습니다. 공개72항목·프록시/포트4항목·PC/390px6회·독립 복원·39개 업무 테이블399행/권한 보존을 확인했고 모바일 긴 셀 겹침도 보완했습니다 ([실제 배포 결과](docs/quality/public-sheet-deploy-20261029.md)).
- 시트/V14 후속 검증: 새33분15,465요청·오류0·p95 323.998ms, 별도 시트135항목과 공개 통합 Java656·React247·Python74개를 통과했습니다. 전체9역할 스캔과 공급자 판정을 마쳤으며 추가 적용 패치 후보0·미수정 위험은 남습니다. 기존 로컬/공개 서비스와 공개43표407행을 보존했고 제품 변경이 없어 `12f0f78`·V14를 유지했습니다 ([최종 부하·보존 결과](docs/quality/sheet-soak-security-20261029.md)·[취약점 상세](docs/quality/sheet-security-20261029.md)).
- 이후 검증된 메뉴 단위도 [후속 배포 절차](docs/development/worklog-public-updates.md)에 따라 공개 보안을 통합하고 기존 PriceSys·demo·portfolio를 보존합니다.
- **Started:** 2026-10-23
- **Development:** Personal Project
- **Internal Name:** `tna` (Time & Attendance)
- **Product Name:** WorkLog (워크로그) · 저장소: `vibe-attendance` ([표시명 적용·검증](docs/development/local.md#worklog-표시명-적용-2026-10-26))
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
- 업무 확장 4단계: React 공통코드 그룹/상세 시트와 별도 근태코드 시트, 적용일별 불변 정책·신청 당시 버전 고정 ([상세 계약](docs/design/code-management.md)·[실행 계획](docs/superpowers/plans/2026-10-28-react-codes.md))
- 4단계 최종 검증: Java618개·React168개/타입·빌드·업무 HTTP129요청·공통 보안24항목·PC/390px6회 관측 통과. 로컬 V14·기존39개 업무 자료/권한 보존·44테이블 합성 논리 복원을 확인했습니다. 별도 V13 공개 배포 작업과 겹치는 공개 작업트리/서버는 변경하지 않았습니다 ([현재 결과](docs/quality/code-management.md)). 신규 병가·반차·시간차 등은 초안이며 신청 활성화는 지원하지 않습니다. 본인 연도별 조회/모달·월간 개선은 후속입니다.
- 업무 확장 3단계 이력: React 결재선 관리·사원/조직별 적용일 배정·조직장 결재·내가 처리한 결재·실제 결재 경로 표시 ([상세 계약](docs/design/approval-routes.md))
- 3단계 당시 검증: Java586개·React152개/타입·빌드·업무 HTTP123요청·공통 보안24항목·PC/390px10회 관측 통과, 로컬 V13 반영·기존35개 업무 자료/RLS 보존·스냅샷을 포함한40테이블 합성 논리 복원 ([당시 결과](docs/quality/approval-routes.md))
- 업무 확장 2단계 이력: React 조직·사원 원자적 시트·조직장 이력·회사 조직도·본인 조직장 표시, 당시 Java548개·React136개·실제 HTTP80개·PC/390px18회 관측·V12와 기존 자료/권한 보존 ([당시 검증](docs/quality/org-sheet-heads.md))
- 권한 확장 1단계 이력: 내 정보·역할별 메뉴·권한 관리·대표 회사 조회, 당시 Java515개·React113개·HTTP51개·브라우저9회와 V11 검증 ([당시 결과](docs/quality/identity-access.md))
- XML·JIT 차단 당시 이미지: 단계별5회 commit/push 후 새33분15,471요청·오류0·7.999req/s·p95 252.791ms, 사전 성능 조사 경고0·원장/Excel/기본 DB 보존 ([부하 재검증](docs/quality/no-xml-soak.md)). 이번 업무 확장 이미지의 재측정은 아님

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
| Language | Java 25 (컨테이너 Eclipse Temurin 25.0.5+8) |
| Backend | Spring Boot 4.1.2 |
| Data Access | MyBatis Spring Boot Starter 4.1.0 / MyBatis 3.6.0 (보안 패치·저장소 회귀 검증) |
| Database | PostgreSQL 18.6, 로컬 PGDG 기반 no-XML/no-LLVM 변형 ([ADR-0004](docs/adr/0004-postgresql-without-xml.md)) |
| Frontend | React 19.3.1, TypeScript 6.0.3, Vite 8.3.5, CSS |
| Security | Spring Security |
| Build | Maven 3.9.16, Maven Wrapper 3.3.4 |
| Migration | Flyway 12.4.0 (웹앱과 별도 실행) |
| Testing | Boot 관리 JUnit 6.0.3, Testcontainers, Vitest 5.0.5 |
| Infrastructure | Docker Compose 로컬·공개 데모, Caddy·Linux VPS; 상용 운영 수용은 후속 |
| 기타 | Spring Boot Actuator; 검토용 XLSX는 JDK 최소 OOXML ([ADR-0002](docs/adr/0002-review-xlsx.md)) |

[ADR-0001](docs/adr/0001-technology-baseline.md)에 백엔드 기준선을, [ADR-0003](docs/adr/0003-react-frontend.md)에 전체 React 전환 근거를 기록했습니다. 직전 보안·부하 검증의 Java489개(단위96·전용 PostgreSQL 통합393)·프론트95개·Python62개·HTTP24개·multipart12개, 33분15,471요청 예상 밖 오류0·p95 247.902ms와 전체 이미지7종 검사·보안 수정·DB 보존은 [최종 보안·부하 보고서](docs/quality/react-soak-security.md)를 따릅니다. 탐색·다운로드·운영자 조회 3건 수정 이력은 [당시 보고서](docs/quality/react-review-fixes.md)에 보존합니다. 최초 전환 이력은 [React 전환 보고서](docs/quality/react-migration.md)에 보존합니다. 이전 하루 흐름 홈의467개·Python42개·HTTP62개·브라우저10개 관측은 [당시 결과](docs/quality/dayflow-home.md)의 이력입니다. 최신 보안 이미지의 실제33분·이미지 검사를 이전 [A안 검사](docs/quality/workspace-ui.md)·홈 이미지 기록과 구분합니다. 고정 일정·출퇴근·연차/정정 승인·집계·Excel·계정 복구·운영자 권한 경계를 포함합니다. 데모 최초 적용 당시 이미지 보안·Ryuk 검증은 [보안 재검사 이력](docs/quality/security-runtime-recheck.md), 이전 Linux 이미지의 HTTP·지속 부하는 [최종 Linux 재검증](docs/quality/runtime-image-soak.md), 이전 업무 연결·격리·동시성·논리 복원은 [기존 품질 보고서](docs/quality/phase6-report.md), 전체 의존성·이미지 보안 패치와 잔여 취약점은 [후속 검사 보고서](docs/quality/http-soak-vulnerability.md), 실행 방법은 [개발 안내](docs/development/local.md)를 따릅니다. 전이 의존성120개의 [라이선스 메타데이터](docs/development/dependency-licenses.csv)는 배포물 전체의 법률 감사 완료를 뜻하지 않습니다.

Phase 6에서는 권한 회수 중 조회·300명 사원 선택 누락·월 조회 직렬화를 수정했습니다. 로컬 합성 부하498개는 오류0이며 동일 고객사 월 조회 p95는2.29→0.85초였습니다. 운영 용량·SLA를 확정하는 결과는 아닙니다.

2026-10-25 당시 Windows native 서버의 후속 HTTP 혼합 부하는18세션·33분·15,471요청에서 오류0, p95 619ms였고32분 steady는8요청/초였습니다. 전체 의존성·빌드 도구와 당시 이미지4종을 로컬 검사해 MyBatis 내부 OGNL·플러그인을 패치하고 migrate 이미지를 줄였습니다. 앱/migrate의 Java archive 검출은0이나 OS·개발 도구의 미해결 항목은 [후속 보고서와 원장](docs/quality/http-soak-vulnerability.md)에 남겼습니다. 이 측정은 아래 최종 Linux 이미지의 부하 성적과 구분합니다.

잔여 OS·개발 도구의 실제 영향과 대응 순서는 [우선순위 보고서](docs/quality/residual-vulnerability-impact.md)를 따릅니다. Noble zlib 패치·마이그레이션 권한 제한·DB의 비루트 시작과 Java 기본 신뢰 저장소의 공식 CA 임시 대응을 검증했습니다. 해당 보완 당시 Linux396개·신뢰 저장소6개·HTTP24개를 통과했습니다. 공급 대기 JDK·libxml2 등 배포 전 조건과 scanner 불일치는 별도로 남깁니다.

2026-10-25 후속 [공식 공급 재확인](docs/quality/security-supply-recheck.md)에서 Windows/Noble JDK와 bookworm libxml2의 공급 대기를 확인했습니다. 새 gosu1.20은 별도 사용자 파일 파싱 수정의 적용 후보이며 기존 Go stdlib 경고 해결판은 아닙니다. OS·개발 도구를 적용 가능 후보·공급 대기·추가 분석으로 분류했으며 이 공급 확인 단위에서는 설치·설정·DB·컨테이너 변경이나 회귀/부하 재실행을 하지 않았습니다.

이후 [gosu 서명·바이너리 분석](docs/quality/gosu-binary-analysis.md)에서 정상 서명2건·변조 거부3건과 고정 DB의1.19/1.20 차이를 확인했습니다. 기존 Medium1건의 취약 심볼 경고는1.20에서 제거됐으며 C/H35건은 심볼 미보고를 무영향 확정으로 바꾸지 않았습니다. 현재 비루트 Compose를 유지하고 gosu 전용 파생 이미지는 도입하지 않으며, 기본 Testcontainers의 별도 root 시작 경로와 공식 이미지 갱신은 추적합니다.

[native 입력·Docker 경계 분석](docs/quality/native-input-docker-boundaries.md)에서는 PostgreSQL ELF943개를 독립 대조하고 실제 JRE의 DNS/libc 경로·선택 라이브러리 의존성을 확인했습니다. 설정을 저장하지 않는 로컬 진단에서 named pipe·classic HTTP1 ping/info를 확인했으며 DockerJava의 P2와 libxml2의 P1은 유지합니다. 분석 중 발견한 미지원 multipart 자동설정은 [후속 보완](docs/superpowers/plans/2026-10-25-multipart-boundary.md)과 아래 이미지 재검증으로 이어졌습니다.

2026-10-26 multipart 설정 보완 단위에서는 업로드 미지원 계약에 맞춰 자동설정을 비활성화했습니다. 기본·local 실제 ConfigData에서 파서/Servlet 설정 bean의 부재를 검증했고, 새 전용 로컬 PostgreSQL에서 당시 전체398개(단위48·통합350)를 통과했습니다. 이 단위에서는 Compose 이미지 재생성·실제 HTTP·부하를 수행하지 않았으며 [당시 결과](docs/development/local.md#미지원-multipart-자동설정-비활성화-2026-10-26)로 보존합니다.

이후 [최종 Linux 이미지 재검증](docs/quality/runtime-image-soak.md)에서 전체 Java402개(단위52·통합350)·Python35개 회귀를 통과하고 새 app/migrate 이미지를 빌드해 기본 app에 반영했습니다. 기본 DB는 보존했으며 새 이미지의 app은 running, migrate는 V10 no-op·종료0입니다. 별도 loopback 합성 DB와 Linux app에서 실제 HTTP24개·multipart12개를 통과했습니다. 최종33분은1,980.205초·15,471요청·예상 밖 오류/논리 실패0·p95 241.014ms이며 종료 정합성·기본 DB 보존과 기본 HTTP24개 재검증도 통과했습니다. 이 단위에는 최종 이미지의 새 SBOM/취약점 재검사와 Ryuk 게시 경계 후속 확인이 포함되지 않습니다.

2026-10-26 후속으로 [로컬 데모 로그인](docs/design/local-demo-login.md)을 적용하고 전체 Java433개·Python35개, 실제 데모 HTTP16개·공통 HTTP24개를 통과했습니다. 최종 이미지4종은 새 고정 Grype DB로 재검사해 원시 매치1,289행을 기록했습니다. 새 CVE3개/5행은 Ubuntu 평가 대기이며 기존 JDK·libxml2 등 미해결 항목은 유지합니다. Ryuk는 실제 loopback 단일 게시·대상/익명 볼륨 자동 정리·다른 자원 보존을 확인했습니다. privileged·root·Docker 소켓 RW 권한은 남습니다. 상세 근거는 [현재 보고서](docs/quality/security-runtime-recheck.md)를 따르며 이번 로그인 이미지로 33분 부하를 재측정하지 않았습니다.

이후 [신규 native CVE 분석](docs/quality/new-native-cve-analysis.md)에서 불필요한 builder curl/libcurl/librtmp를 제외하고 기존 Java downloader로 cold 빌드를 통과했습니다. 새 이미지4종 재검사는1,234행·제외0이며 builder의 RTMP2건은 패키지 부재로 검출되지 않습니다. Brotli3행과 기존 P1은 유지하며 CNA9.2/Critical과 Ubuntu Medium을 구분합니다. Java433개·Python35개·실제 HTTP16+24개와 기존 DB 보존을 다시 확인했습니다. 해당 native 보완 단위에서는 제품 UI를 변경하지 않았으며, 이후 [A안 승인·실제 적용](docs/quality/workspace-ui.md)으로 소개·홈·일정·직원 목록과 데모 진입을 연결했습니다.

## Local Development

현재 로컬 PostgreSQL은 같은18.6 source를 XML/XSLT/LLVM 없이 패키징한 `18.6-1.pgdg12+2+worklog1`이다. 실행 이미지에서 libxml2와 관련 소비자를 제거했으며 XML·JIT는 지원하지 않는다. 해당 구성 도입 당시 upstream SQL231개·Java496개·Python62개·실제 HTTP/Excel/승인과 기존 물리/논리 백업 복원·기본 DB35개 테이블 보존을 검증했다. [당시 보고서](docs/quality/libxml-containment.md)는 libxml 자체 패치와 현재 runtime 경로 제거를 구분하며 다른 잔여 경고·업데이트 유지 조건을 기록한다.

2026-10-27 잔여 보완에서 PostgreSQL의 불필요 GPG·SQLite 16개 패키지를 제외하고 공식 테스트 설정으로 Ryuk privileged 모드를 껐습니다. 최종 Java493개·Python62개·실제 HTTP/백업 복원·자동 정리·기본 DB35개 테이블 보존을 통과했습니다. 변경 PG2종 재검사와 기존5종의 고정 결과를 대조한 원시 매치는2,035→1,911행이며 유일 CVE 수가 아닙니다. 실제 SQL XML 경로와 공급 대기·빌드/테스트 경계는 [서비스 영향 보고서](docs/quality/react-residual-impact.md)를 따릅니다. 프론트95개·33분 부하는 위 직전 검증의 이력입니다.

2026-10-27 착수한 업무 확장 2단계는 2026-10-28에 조직·사원 시트 원자 저장, 조직장 적용일 이력, 관리자/대표 조직도와 본인 조직장 표시를 로컬 검증했습니다. Java101+447=548개·React136개/11파일·타입/Vite·HTTP56+24=80개와 PC/390px18회 관측을 확인했습니다. V12 추가 후 업무35개+Flyway1개 총36개 테이블이며 기존 업무34개의 전체 행·RLS/ACL/컬럼 권한과 DB 컨테이너·이미지·볼륨을 보존했습니다. 새 이미지의33분 부하·전체 취약점 검사·Python 재실행·외부 배포는 하지 않았습니다. 모바일 수정과 최종 실행 JAR 대조·실제 수행 범위는 [2단계 보고서](docs/quality/org-sheet-heads.md)를 따릅니다.

고정 근무의 핵심 흐름과 계산·감사 상세·서울 시각 표시를 로컬에서 검증한 상태이며 운영 서비스는 아닙니다. 로컬 환경의 준비·실행·테스트 명령은 [개발 안내](docs/development/local.md)를 따릅니다. 기본 주소는 `http://127.0.0.1:18080`입니다. 현재 Compose의 `local,local-demo`에서는 소개 화면의 **데모로 입장** 버튼으로 입력 없이 들어갑니다. 기존 공개 합성 `admin / admin`을 정상 CSRF POST 인증에 연결했습니다. 합성 로컬 데모 전용이며 명시 CLI로 자료를 준비합니다. 일반 프로필은 회사코드·이메일 인증과 기존 비밀번호 정책을 유지합니다. 흰색·파란 업무 테마의 하루 흐름 홈·개인 달력·직원 목록과 담당자의 조직·소속·계정·역할·당일 승인자 관리 화면을 제공합니다. 실제 일정·기초 연차가 없는 경우 미배정·미등록을 표시합니다. 초대는 `local` 프로필의 메모리 모의 전달만 지원하며 외부 메일을 발송하지 않습니다. 사원 등록으로 로그인 계정을 자동 생성하지 않으며 비밀번호 재설정은 같은 메모리 모의 전달을 사용하며 최초 고객사 온보딩은 별도 운영자 UI의 명시적 활성화와 독립 로컬 부트스트랩을 사용합니다.

Java 25와 Node.js 22.12 이상에서 `mvnw.cmd test`는 프론트 잠금 설치·타입/빌드와 Java 단위 테스트를, `mvnw.cmd verify`는 DB 통합 테스트까지 실행합니다. 프론트 단위 검증은 `npm --prefix frontend test`, Vite 개발 서버는 백엔드 실행 후 `npm --prefix frontend run dev`이며 `http://127.0.0.1:5173`에서 사용할 수 있습니다. 통합 테스트에는 Docker 또는 안내에 명시한 전용 로컬 테스트 PostgreSQL이 필요합니다. 기존 서비스의 DB를 테스트 대상으로 재사용하지 않습니다.

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

- [React 결재선 관리·조직장 결재 검증](docs/quality/approval-routes.md): 확장 3단계의 회귀·실제 화면·V13와 기존 이력 보존 실행 근거
- [1단계 결재선·조직장 결재](docs/design/approval-routes.md): 정의 시트·불변 버전·사원/조직 배정·신청 스냅샷·기존 이력 보존
- [React 조직·사원 시트·조직장·조직도](docs/quality/org-sheet-heads.md): 원자적 배치·오류/최신값 보존·V12·전체 회귀·실제 HTTP/화면과 기존 DB 보존
- [React 권한·메뉴·내 정보](docs/quality/identity-access.md): 대표 회사 읽기·관리자 변경 분리, 현재 역할·본인 조회, 전체 회귀·V11·실제 로컬 검증과 남은 범위
- [React 전체 전환](docs/quality/react-migration.md): 전체 화면의 React·JSON/폼 계약·회귀·로컬 실행·브라우저 검증, [기술 결정](docs/adr/0003-react-frontend.md)
- [XML·JIT 차단 구성의 장시간 부하](docs/quality/no-xml-soak.md): 기존 변경의 단계별 게시·새33분 부하·이전 구성 비교·자원/종료 정합성
- [WorkLog 사용자·관리 업무 확장 설계](docs/superpowers/specs/2026-10-26-worklog-service-design.md): 1단계 권한·메뉴·내 정보, 2단계 조직·사원 시트·조직장·조직도, 3단계 결재선의 React 구현과 단위별 로컬 검증. 권한/코드 시트·본인 연도별 모달·월간 개선과 운영 수용은 후속
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
- [Organization Management](docs/design/org-management.md): 기존 단건·소속 관리와 React 조직·사원 배치·조직장·조직도 계약
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
- [Local Demo Login](docs/design/local-demo-login.md): 입력 없는 소개 버튼·기존 로컬 데모 인증 계약
- [Workspace A Validation](docs/quality/workspace-ui.md): 승인 시안 실제 적용·전체 회귀·HTTP·데스크톱/모바일·새 app/migrate 검사
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
- [x] 대표 역할·React 역할별 메뉴·내 정보·권한 관리 진입·회사 읽기와 V11 로컬 검증
- [x] React 조직·사원 원자적 시트·조직장 적용일 이력·회사 조직도·본인 조직장 표시와 V12 로컬 검증
- [ ] 새 결재선·권한/공통/근태코드 시트·본인 연도별 모달·월간 현황 개선
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
