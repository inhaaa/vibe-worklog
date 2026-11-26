# ADR-0007 계정 초대와 비밀번호 복구 메일

2026-11-21 결정. 처음 접속하는 담당자와 직원은 받은 메일에서 회사와 다음 행동을 확인할 수 있어야 한다. 계정 서비스에서 발급한 일회용 코드는 DB 커밋 후에 전달한다.

## 전달 방식

- 메일에는 발급 트랜잭션에서 저장한 만료 시각을 서울 시각으로 표시한다. 같은 용도의 초대를 재발급하거나 복구 코드를 다시 요청한 경우, 수신자는 만료 시각을 대조해 사용할 메일을 고를 수 있다.
- `tna.mail.mode=smtp`, `tna.mail.from`, `tna.mail.public-origin`과 `spring.mail.host`를 설정한다. SMTP 서버 포트와 인증 정보는 `spring.mail`의 표준 설정을 사용한다. 기본 연결·읽기·쓰기 제한 시간은 각각 5초·3초·5초이고 STARTTLS와 서버 이름 검증을 적용한다. 평문 수신을 사용하는 루프백 테스트 메일함에서는 `spring.mail.properties[mail.smtp.starttls.enable]`과 `spring.mail.properties[mail.smtp.starttls.required]`를 `false`로 지정한다.
- 메일 주소는 한 개의 우편함만 받는다. 등록된 사이트 origin은 HTTPS 또는 로컬 테스트용 루프백 HTTP를 받는다. 메일에는 입력 화면 주소, 회사 코드, 일회용 코드와 유효시간을 담는다. 일회용 코드는 메일 본문과 수신자가 제출하는 POST 본문에만 넣는다.
- 초대 링크는 기존 `/invite/{회사코드}` 경로를 사용한다. 경로 구분자나 점 세그먼트 등을 포함한 기존 회사 코드는 `/invite?companyCode=...` 한 개의 쿼리 값에 인코딩하며 이 폼은 `POST /invite/accept`에서 같은 수락 절차를 사용한다.
- 일반 프로필에서 전달 방식을 설정하지 않으면 발급이 중단된다. `local` 프로필에서 별도 방식이 없으면 기존 메모리 메일함을 사용한다. `public-demo`는 계정 메일 발급을 차단한다.
- 초대 발급 후 SMTP 수락을 확인하지 못하면 운영자 또는 담당자에게 이미 등록된 초대와 재발급 방법을 안내한다. 동일 명령 재시도는 회사·계정을 한 번만 생성한다. 초대 화면에서 새 명령으로 같은 주소에 재발급하면 이전 코드는 폐기된다.
- 공개 비밀번호 복구 응답이 수신 여부나 SMTP 지연을 드러내지 않도록 커밋된 발급 건만 메모리에서 비동기 전송한다. 큐 포화·전송 실패·프로세스 재시작으로 메일이 도착하지 않으면 다시 요청할 수 있다. 재요청 때 기존 코드를 폐기한다. 전송 실패 로그에는 목적과 결과만 남긴다.

## 결재 알림 메일 (2026-11-26)

결재 알림 메일도 같은 `tna.mail.*`·`spring.mail` 설정을 따른다. `local` 프로필에서 전달 방식이 없으면 메모리 메일함에 쌓고, `public-demo`이거나 전달 방식이 없으면 보내지 않는다. 링크 주소는 `tna.mail.public-origin`이 없으면 `http://127.0.0.1:18080`을 쓴다. 발송은 1분 주기 작업이 트랜잭션 밖에서 하므로 서버가 발송 직후 멈추면 같은 메일이 한 번 더 갈 수 있다. `tna.notification-mail.enabled=false`로 발송 작업을 끌 수 있다. [설계](../superpowers/specs/2026-11-26-approval-notifications-design.md)

## 의존성 근거

기존 Spring Boot 4.1.2가 버전을 관리하는 `spring-boot-starter-mail`을 추가했다. 프레임워크의 JavaMailSender와 Jakarta Mail API 2.1.5, Eclipse Angus Mail 2.0.5가 UTF-8 메일 인코딩과 SMTP 전송을 담당한다. 같은 버전의 starter와 mail 모듈은 Apache 2.0이고 Jakarta 및 Angus 배포물의 개별 라이선스는 Maven POM에서 확인했다. [Spring Boot 4.1 메일 설정](https://docs.spring.io/spring-boot/4.1/reference/io/email.html) · [Spring Boot 4.1.2 mail 모듈 의존성](https://github.com/spring-projects/spring-boot/blob/v4.1.2/module/spring-boot-mail/build.gradle) · [Eclipse Angus Mail](https://eclipse-ee4j.github.io/angus-mail/) · [해석한 좌표와 라이선스](../development/dependency-licenses.csv)
