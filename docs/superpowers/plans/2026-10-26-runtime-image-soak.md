# multipart 설정 반영 이미지·실제 HTTP·혼합 부하 재검증

**범위:** 승인된 Phase 6 로컬 검증으로 최종 Linux 이미지를 재빌드하고 기존 Compose 앱에 반영한다. 기존 DB 컨테이너·볼륨은 보존한다. 부하 데이터는 새 Testcontainers 전용 DB에만 생성한다.

**검증 계약:** 기존 HTTP 24개 검사, URL-encoded 로그인 성공, multipart의 CSRF 없는 요청 403·정상 CSRF 헤더를 넣어도 multipart 자격으로 로그인되지 않음을 확인한다. 테스트 도구는 합성 fixture 표식·loopback URL·target 경로만 허용하고 비밀값을 출력하지 않는다. 기존 multipart 활성 fixture에서 실패하는 대조군도 확인한다.

**부하 계약:** 새 Linux 앱 이미지의 정확한 ID를 기록하고 전용 bridge 네트워크·loopback 포트·비루트·읽기 전용 루트·cap_drop ALL·NNP로 실행한다. fixture는 기존 127/32 이력을 보존하고 검증한 사설 IPv4 gateway 하나의 /32만 추가한다. 60초 smoke 후 1,980초·8요청/초·60초 ramp 혼합 부하를 별도 기록한다. Linux 앱/DB Docker 지표와 Windows seed JVM 지표를 구분한다.

사전 진단에서 internal 전용 네트워크의 앱은 기동했으나 실제 게시 포트가 빈 배열이었다. 첫 대조군을 정상 종료하고 별도 일반 bridge·명시적인 loopback 게시와 새 합성 fixture로 재준비했다. internal/no-egress 격리를 검증했다고 표현하지 않는다.

첫 혼합 측정 중 독립 inspect에서 기본 Testcontainers DB 게시가 `0.0.0.0`/`::`임을 발견해 측정을 중단했다. 이 부분 실행은 성공 성적에 포함하지 않는다. `DatabaseFixture`의 새 컨테이너에만 명시적인 `127.0.0.1` 임의 포트 바인딩을 적용하고, 단위 RED→GREEN·실제 inspect·전체 회귀 후 새 fixture에서 전체 기간을 다시 측정한다. 공급받은 JDBC 경로와 기본 서비스 DB는 변경하지 않는다.

- [x] 테스트 도구·fixture 인터페이스를 먼저 테스트하고 RED→GREEN을 확인한다.
- [x] 기존 DB의 컨테이너 ID·볼륨·집계·Flyway·RLS·권한을 읽기 전용으로 사전 기록한다.
- [x] 빌드 전용 secret으로 app/migrate를 빌드하고 migrate 무변경 성공 후 앱만 재생성한다. HTTP 24개와 실행 경계를 확인한다.
- [x] 별도 합성 fixture와 final Linux 이미지에서 multipart 대조·실제 HTTP·smoke·33분 부하를 검증한다.
- [x] 종료 후 replay 중복·집계, 기존 DB 보존, 코드 회귀·문서·diff를 확인한다.
- [x] 실제 결과와 제한을 문서화하고 관련 파일만 커밋·일반 Push한다.

운영 수용·외부 배포·미해결 JDK CPU/libxml2 공급 문제의 해결을 선언하지 않는다. 부하 생성기도 같은 Windows 호스트이며 이 검증은 운영 용량 보장이 아니다.

실행 결과: loopback 수정 후 Java402개(단위52·통합350)·Python35개·기본/합성 Linux HTTP 각24개·실제 multipart12개가 통과했다. 최종 1,980.205초·15,471요청에서 예상 밖 오류/논리 실패0, p95 241.014ms다. 과거55,800건 지문 보존·오늘18개 원천·재전송 중복0, 연차 기초1,200-사용330=잔여870과 승인/대기 집계가 일치했다. 먼저 생성한 출력3개 지문과 기존 DB의 ID/볼륨/권한/집계도 같고 fixture 종료0을 확인했다. 결과·제한·중단 기록은 [보고서](../../quality/runtime-image-soak.md)에 구분한다.
