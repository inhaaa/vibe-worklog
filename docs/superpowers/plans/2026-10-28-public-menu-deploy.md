# 최신 완료 메뉴 공개 데모 배포 실행 계획

**목표:** 개발 완료 `ecf6dfc`(지침 포함 `6906e8c`)의 V13 메뉴를 기존 공개 보안을 유지해 `worklog.inhaaa.com`에 배포한다. 개발 폴더의 작성 중인 V14는 제외한다.

**범위:** 전용 공개 배포 worktree·`/srv/worklog`·`worklog-demo`·`worklog_demo`. 기존 PriceSys·demo·portfolio의 자원과 DNS/Caddy는 유지한다. 2026-10-28 사용자가 최신 완료 메뉴의 보안·테스트·외부 배포를 명시 승인했다.

- [x] 개발 커밋/dirty 작업 구분, 전용 배포 branch에 완료 커밋만 통합 준비.
- [x] 신규 일괄 사원 경로의 공유 계정 변경 차단 RED→GREEN, 공개 보안 독립 리뷰.
- [x] 통합 Java/PostgreSQL·React/타입/빌드·Python 배포 계약 회귀.
- [x] 서버 기존 서비스·WorkLog 이미지/스키마·설정 해시·여유 자원 기록.
- [x] 검증 소스 SHA 고정 전송, WorkLog 전용 제한 빌더로 앱/migrate 준비.
- [x] V10 백업→독립 전용 DB 복원→V11~V13 시험과 기존 자료/RLS/권한 대조.
- [x] WorkLog 앱만 중지→최종 백업→전용 migrator→후보 앱만 시작.
- [x] 공개 HTTPS·로그인·보안·변경 메뉴/PC·모바일·기존 사이트와 컨테이너 전후 대조.
- [x] 새 임시 연결 생성 없이 기존 해제 상태 확인, 실제 결과·한계 문서화와 전용 branch 커밋.

실제 결과와 미실시 범위는 [배포 검증 보고서](../../quality/public-menu-deploy-20261028.md)를 따른다. 런타임 소스는 `efd13ea`이며 이후 문서 커밋과 구분한다.

bootstrap/sample 재실행과 전역 정리·서비스 전체 재시작을 하지 않는다. 스키마 적용 뒤에는 구 앱만 무조건 되돌리지 않으며 자료 손실 복원은 자동 실행하지 않는다. 실패 단계·적용 이력부터 확인한다.
