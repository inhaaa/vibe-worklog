# 공개 시트 직접 편집·V14 배포 결과

기준일: 2026-10-29, Asia/Seoul. 대상은 가상 자료 공개 데모 [worklog.inhaaa.com](https://worklog.inhaaa.com)이다. 기존 시트 구현의 로컬 검증은 [셀 편집](sheet-cell-editing.md)·[공개 통합](public-sheet-release-20261029.md)에 보존한다.

## 실제 반영

- 최종 앱 시작: **2026-10-29 11:00:12 KST**, 최종 서버 상태 수집11:05:33 KST.
- 최종 공개 소스: `12f0f78b9da34b99a8337f7567d7bf6abfc4720f`. 최종 제품 보완은 `17a9e25`의 셀 wrapper 폭 한 줄이다. 작은 구현·문서 단위 Commit/일반 Push를 수행했다.
- 앱 이미지: `sha256:78bbfe3e96f637b9fb993a9e16993fe1311b6406bbf0fe53b88e62f2f9904198`. migration 실행은 1차 `d8a8b03` 이미지 `sha256:d8e802dd00938061ea3461bf0abb5a546b73123934b699636d2e28fe00241b63`로 수행했다. 후속 CSS 앱 교체에서는 migration을 실행하지 않았다.
- 최종 서버 JAR SHA256: `52e22e478c6fbc5f21ff19344a82c71866602a414a35536d0c602129041464ff`. 로컬 최종 dist와 서버 JAR의 React8파일 집합·SHA가 일치했다. `app.js`는 `80a2c51de81b60872164bd2e0098d6b26a05714e531b54c6306e5c23147069f2`, 보완 `sheet.css`는 `f330fa8e2379a902da869e5feee20446a274abcc601ca95e10617d503de27492`다.
- `/srv/worklog`·Compose `worklog-demo`·DB `worklog_demo`·기존 전용 볼륨/네트워크를 유지했다. 앱은 non-root10001·read-only rootfs·loopback `127.0.0.1:18081`만 게시하고 DB는 비공개다. `.env`는0600이며 이미지 태그 외 비밀값·프록시 값은 바이트 단위로 보존했다.

## 백업·독립 복원·V14

실제 V13을 읽고 WorkLog 앱만 잠시 정지해 새 전열 해시와 custom dump를 만들었다. 기존 앱을 먼저 재개한 뒤 독립 DB `worklog_demo_restore_sheet_v14_d8a8b03`에 single-transaction으로 복원하고 exact 비교를 통과했다. 복원 DB에만 별도 migration 역할로 V14를 시험한 후 실제 DB 최종 백업→V14→자료/권한 비교→후보 앱의 순서로 반영했다. bootstrap·sample-data·기존 이력 수정은 하지 않았다.

보존 비교 도구의 변조·권한·제약 회귀15개를 로컬과 서버에서 통과했고, 리허설의 정지/백업 실패 후 앱 재개·대상 격리 등 오프라인7개도 통과했다. 이 도구 검사 수를 제품 테스트와 합산하지 않는다.

기존39개 업무 테이블의399행·모든 원래 열의 해시와 열 계약·기존 Flyway를 보존했다. 테이블/열 ACL·RLS·정책·제약·인덱스·함수 본문/SECURITY DEFINER/고정 search_path·사용자/RI trigger·default ACL·역할 membership도 비교했다. V14 gold에 고정한 신규4테이블·신청 정책버전 열/NOT NULL/FK·기초 생성 함수/트리거와 한정 권한 추가만 허용하며 리허설과 실제 스키마/보안이 같다. Flyway V14·실패0이다.

공개 DB의 tenant1개에서 그룹1·상세3·근태유형2·정책2가 생겼고 잘못된 기초/신청 연결/임시 정책0이다. **기존 공개 신청은0건**이므로 실제 공개 자료만으로 비어 있지 않은 신청의 호환을 검증한 것은 아니다. 해당 범위는 별도 로컬 PostgreSQL의2tenant·8신청 upgrade 회귀를 포함한 앞선 Java39개 검증을 따른다. 일반 앱 역할의 신규4테이블 읽기 검사는 무컨텍스트·해당 tenant 수량·그 컨텍스트의 다른 tenant 행 조건0·미존재 tenant0·새 무컨텍스트 연결5항목을 통과했다.

백업은 서버 접근 제한 경로 `/srv/worklog/.local/sheet-v14.d8a8b03`에 유지했다. rehearsal.dump242,114bytes SHA256 `1aa07fb8b2dcc1479d69d131605fe133d67ee2e5c629243084402d32ca33af18`, final.dump242,114bytes SHA256 `e223a9eb39f14b706be5af765556723dd683e1992f24ff7ebd662b60a0d6cd1c`다. 독립 exact 복원은 rehearsal dump, 최종 dump는 TOC 검증 범위다. 덤프·환경 비밀값·원문 행은 Git에 넣지 않았다. V14 후 이전 V13 앱만 복귀시키지 않았다.

## 공개 검증과 표시 보완

- 최종 HTTPS 보안/메뉴72항목: 가상 안내·정상 CSRF 로그인/로그아웃·Secure/HttpOnly/SameSite 쿠키·공유 계정 단건/대문자 UUID 일괄 변경 차단·미인증 업무·운영자/진단 차단·새 코드 메뉴/API CSRF.
- 프록시·포트4항목: HTTP301 경로/query 보존, 위조 forwarded scheme/host 거부·HSTS, 공개IP의 앱18081 접속 차단.
- 기존10개 컨테이너의 이름/ID/시작/이미지와 running/paused/restarting/dead/OOM/exit/restart/종료 상태를 대조했다. Caddy SHA256 `da4f8a24e779169fe8522eba7d99883612149422fad6acc8e9232aa840b0e059`도 그대로다. PriceSys401, 기존 demo health200, portfolio200, WorkLog health200을 전후 확인했다. DNS·Caddy·다른 프로젝트·전역 Docker는 변경하지 않았다.
- 최종1280px PC·390px 모바일에서 사원/공통코드/근태코드3화면씩6회 관측했다. 평소 편집기0개·클릭1개, Tab 이동·Esc 원값 복원·전체 취소, 선택 표시명·연관 적용일 취소 복원, 필수3개 오류/첫 편집기 focus/aria-invalid/실제 설명 연결을 확인했다. 모든 초안을 취소했으며 화면 최종 변경0행이다. 문서 폭은PC1265px·모바일375px로 가로 넘침0, 시트 내부 scrollLeft0→375를 실제 확인했다. 브라우저 warn/error0, 임시 viewport 원복과 스크린샷6개를 보존했다.

공개390px 점검에서 `ATTENDANCE_CORRECTION` wrapper가160px 셀 경계를15.875px 넘는 RED를 발견했다. AG wrapper의 flex 최소 폭 때문에 하위 ellipsis가 작동하지 않는 원인을 확인해 `min-width:0;width:100%` 한 줄로 보완했다. 원본 선택3파일49개·타입/Vite build와 공개 후보 build를 통과했다. 후속 공개390px과PC에서 해당 wrapper 오른쪽이 셀 오른쪽보다10px 안쪽에 있는 GREEN을 확인했다. 긴 값은 말줄임으로 표시하며 기존 title로 전체 값을 확인할 수 있다. 일반 패키지/보안/DB 설정은 바꾸지 않았다.

전용 빌더는1.5GiB·CPU0.75 제한을 지켰고 작업 후 중지했다. 최종 디스크 여유는16.89GiB였다. 후속 shell script의 CRLF로 빌드 진입 전 한 번 실패한 기록을 보존하고 LF/구문 검사 후 성공했다. 첫 불변 소스428파일, 후속429파일의 전체 집합/바이트/모드를 Git manifest로 대조했다. private GitHub archive404는 자격 우회 없이 로컬 Git archive의 SHA 검증 전송으로 처리했다.

## 범위와 남은 검증

원본 고정246개 전체와 후보 AuthPages9개·Java39개·Python7개 결과는 검증된 소스/설정 동일 범위에서 재사용했으며 이번 배포에서 전체 회귀를 재실행했다고 표현하지 않는다. CSS 보완49개는246개와 합산하지 않는다. 이번 실제 공개 점검은 합성 계정·자료로 수행했고 UI 편집 초안은 모두 취소했다. 업무 데이터의 새 양성 저장/승인 시나리오, 실제 고객·실제 메일·새 장시간 부하·새 전체 이미지 취약점 검사는 수행하지 않았다. Vite 기존500KB chunk 경고는 남는다.

원시 증거·스크린샷·HTTP 결과는 ignored `target/sheet-deploy-20261029/`, 서버 명세/덤프/로그는 위 전용 접근 제한 경로에 보관한다. 코드/문서 Commit·Push와 공개 반영을 구분하고 배포 후 문서 단위는 런타임 제품 변경 없이 기록만 갱신한다.
