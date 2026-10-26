# 신규 native CVE 분석·빌드 입력 보완

사용자는 신규 CVE3개 분석, 적용 가능한 보완과 테스트 후 커밋·일반 Push를 승인했다. 화면 개편은 별도 디자인 안건의 승인 전까지 구현하지 않는다.

1. 공식 Ubuntu·CVE CNA·upstream 자료와 고정 이미지의 실제 소비자/입력을 확인한다. scanner 우선순위·CNA 심각도·배포판 공급 상태를 구분한다.
2. Brotli decoder의 앱/폰트/압축 입력과 builder curl의 RTMP 입력을 구분한다. 배포판 미평가 패키지를 임의 제거·수동 덮어쓰기하지 않는다.
3. curl이 있는 Linux 환경의 Wrapper 다운로드를 HTTPS 초기 URL·HTTPS redirect로 제한하고 자동 curlrc를 읽지 않게 한다. 현재 builder는 curl 설치 자체를 제외하고 기존 Java fallback을 재사용한다. TLS 검증·배포물 SHA256과 기존 인증/RLS는 유지한다. 실제 고정 builder에서 각 변경의 RED→GREEN을 확인한다.
4. 같은 테스트의 성공, 실제 HTTPS cold 이미지 빌드, 전체 회귀와 실행 HTTP를 확인한다. 바뀐 이미지/SBOM·원시 취약점 상태와 미해결 범위를 기록한다. builder 소비자 부재와 라이브러리 패치를 구분한다.
5. 문서·diff·작업 범위와 비밀정보 부재를 검토하고 커밋·일반 Push, 원격 일치·clean 상태를 확인한다.

화면은 참고 사이트의 공개 화면·실제 저장소 경로를 조사해 별도 [디자인 제안](../../design/ui-redesign-proposal.md)을 작성하고 사용자 승인을 기다린다. 로그인 입력 생략·합성 데모 진입도 승인 후 정상 인증·세션·권한 경계 안에서 구현한다.

실제 [검증 결과](../../quality/new-native-cve-analysis.md): Java433개·Python35개·실제 HTTP16+24개 통과, 고정 이미지4종1,234행·제외0, builder RTMP2건은 패키지 부재로 검출되지 않음. Brotli3행·기존 P1은 유지한다. 화면 개편은 제안서만 작성했으며 승인 전 구현하지 않는다.
