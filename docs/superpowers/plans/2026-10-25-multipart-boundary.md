# 미지원 multipart 자동설정 경계 보완 계획

**목표:** 파일 업로드가 없는 MVP의 기본·local 프로필에서 multipart parser/config 자동등록을 중지한다. 새 업로드 기능·차단 필터·DB 변경은 추가하지 않는다.

**계약:** [보안 설계](../../design/security.md#7-웹세션출력-방어)에 따라 Boot의 `spring.servlet.multipart.enabled=false`를 공통 설정에 명시한다. `MultipartResolver`·`MultipartConfigElement`가 없는 것을 실제 ConfigData 로드와 Servlet 자동설정으로 검증하며, 기존 URL-encoded 폼·CSRF·htmx·Excel 다운로드는 회귀로 확인한다. 현재 사용자의 안전한 품질 보완·로컬 설정·검증·커밋·일반 Push 승인 범위에서 진행한다.

- [ ] `MultipartConfigurationTest`를 먼저 작성하고 현재 설정에서 실제 실패를 확인한다. 기본/local 두 프로필에서 property와 자동등록 bean을 검사하며 DB 없이 실행한다.
- [ ] `application.yml`의 공통 Spring 설정에 multipart 비활성화만 추가하고 같은 테스트가 통과하는지 확인한다. local에는 중복 설정하지 않는다.
- [ ] 단위·PostgreSQL/웹 회귀를 실행해 기존 폼·권한·테넌트·출력 경계를 확인한다. 실제 실행 범위와 건너뜀을 보고한다.
- [ ] 보안 설계·개발 안내·README/분석 보고서의 시점을 구분해 기록하고 diff/status·문서·새 파일을 검증한 뒤 이 단위만 커밋·Push한다.

분석 당시 자동설정 가능성과 코드 변경 후 검증을 구분한다. 기본 파서 등록 중지로 모든 MIME 요청의 거부·컨테이너 임시 파일 동작·전체 native 취약점 해결을 주장하지 않는다.
