# 흰색·파란 업무 테마의 하루 흐름 홈 적용 계획

> 사용자 결정: 이전 흰 배경·파란 강조의 업무용 디자인을 유지하고, 홈만 새 시안의 하루 흐름 배치로 적용한다. 기존 실제 적용 요청의 검증·커밋·일반 Push 범위를 이어간다. 독립 파일 담당을 나누고 TDD와 완료 전 검증을 따른다.

**Goal:** 홈의 큰 날짜·예정/반영 기록 타임라인·확인할 일·주간 띠·원천/반영 비교를 기존 실제 자료와 흰색/파란 테마로 제공한다.

**Architecture:** 기존 `WorkspaceQueries`의 본인·테넌트 읽기 결과를 재사용한다. `DayView.timeline()`은 예정 시각과 현재 반영 사건을 서울 시각으로 정렬하는 순수 표시 함수다. 기존 공통 sidebar/topbar와 다른 화면, 인증·CSRF·명령 ID·현재 권한 검증·DB·계산 정책을 유지한다. 상세는 native `details`와 기존 날짜별 기록/신청 링크를 사용한다.

**Tech Stack:** 현재 Java25·Thymeleaf·Spring Security·MyBatis·CSS. 새 라이브러리·프레임워크·외부 자원·환경 설정을 추가하지 않는다.

## 먼저 고정한 인터페이스

- `WorkspaceData.TimelineEntry(LocalTime time, String title, String detail, String kind)`, `DayView.timeline(): List<TimelineEntry>`.
- `kind`는 `planned` 또는 `recorded`. 근무일의 실제 존재하는 예정 시작/종료·휴게 시작/종료와 `snapshot.effectiveEvents()`를 정렬한다. 시각이 없으면 가상의0시를 넣지 않으며, 중복/동일 시각 사건도 보존한다. 표시 정렬은 실제 근로 계산이나 지각·연장·결근 판정이 아니다.
- `today.html`은 기존 모델·POST 폼을 사용하며 홈 main에만 `t-home-flow`를 적용한다. `t-flow-header/date/layout/timeline/entry/side/clock/tasks/week/days/day/comparison`을 CSS 경계로 사용한다.
- `#home-record-comparison`의 접이식 비교는 원천 `day.events`와 현재 반영 `day.effectiveEvents`를 분리한다. 원천을 덮어쓰거나 승인 정정을 퇴근 버튼의 효과로 표현하지 않는다.
- 큰 날짜와 출퇴근·이름·잔여·신청 상태·주간7일은 실제 읽기 결과다. 합성 시안의 김하늘·08:57·09:02·승인 연차를 실제 화면에 하드코딩하지 않는다.
- 확인할 항목은 현재 자료의 미배정·미등록·현재 반영 IN·OUT이 모두 있고 실제 근로 미확인인 경우의 확인 진입·본인 대상 대기 신청 등을 안내한다. 새로운 기한·의무·위반·승인 권한을 만들지 않는다.
- 주간 날짜는 기존 `/me/schedule?month=YYYY-MM&date=YYYY-MM-DD`로 연결한다. 데모 소개/입장·공통 메뉴·일정·직원 목록 레이아웃은 현재 상태를 유지한다.

## 실행 단위

- [x] 순수 타임라인 회귀를 먼저 작성한다. SDD 계약의 빈 반환 stub만 만든 뒤 RED를 확인한다. 예정/반영 사건의 서울 시각 정렬·미배정·비근로일·동일/중복 사건을 검증한다.
- [x] 기존 독립 tenant fixture의 `WorkspaceWebIT`에 새 홈 구조·비교/링크/기존 명령 보존 회귀를 먼저 추가하고 실제 assertion RED를 확인한다.
- [x] 순수 표시 함수와 홈 SSR을 구현한다. CSS는 `#tna-app .t-home-flow` 아래로 한정하며 기존 흰/파란 token을 재사용한다. 다른 템플릿·공통 fragment·JavaScript를 바꾸지 않는다.
- [x] 관련 Java 검증 GREEN 뒤 전체 `verify`와 기존 Python 도구 회귀를 실행한다. 기본 호스트 JDK를 바꾸지 않고 프로젝트 JDK25를 자식 프로세스에 주입한다.
- [x] app/migrate 새 이미지를 빌드하고 로컬 적용한다. 최종 검증 JAR의 production 항목 일치·V10 no-op·기존 DB/볼륨/권한/RLS 보존·HTTP를 확인한다.
- [x] 실제 브라우저에서 홈·비교 펼침·날짜 이동·모바일 메뉴와 다른 메뉴의 기존 디자인을 확인한다. 1024/390/320px에서 가로 넘침을 점검한다.
- [x] README·PRD·UI 계약·개발 안내와 실제 결과를 동기화하고 독립 검토·문서 링크·diff/status·새 파일 내용을 확인한다.

관련 실행 명령은 JDK25의 `mvnw.cmd verify`, `python -B -m unittest discover -s scripts -p 'test_*.py'`, 로컬 앱의 `python -B scripts/verify-local-http.py --port 18080`다. 작업별 RED/GREEN과 추가 HTTP/DB 보존/JAR 대조 원시 결과는 Git 제외 `target/dayflow-home-20261026/`에 보존한다.

외부 배포·실고객 자료·메일 발송·추가 업무 정책·DB 스키마·전역 환경 변경은 범위 밖이다. 새 이미지에 이전 이미지의 취약점 검사나33분 부하 성적을 그대로 적용하지 않는다. 이번 홈 단위에서 별도 SBOM/취약점·지속 부하를 재실행하지 않으면 미실시로 기록하고 기존 미해결 보안 조건을 유지한다.

실제 결과는 [홈 검증 보고](../../quality/dayflow-home.md)에 기록했다. 승인된 마무리 절차는 이 변경 단위의 일반 커밋·Push 후 원격 SHA와 clean 상태 확인이며, 결과는 Git 이력과 최종 완료 보고로 확인한다.
