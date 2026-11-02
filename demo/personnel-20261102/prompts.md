# 공개 가상 데모 사원 사진 생성 기록

기준일: 2026-11-02, Asia/Seoul.

- 목적: WorkLog 공개 가상 데모 사원 4명의 공통 사원 헤더를 위한 공개 데모 전용 합성 사진. 실제 직원 사진으로 사용하지 않는다.
- 생성: `imagegen` skill의 기본 built-in `image_gen.imagegen` 모드. 자산마다 별도 호출했으며 외부 사진 검색·CLI/API fallback·참조 이미지는 사용하지 않았다.
- 인물: 전원 AI로 생성한 가상 한국인 성인이다. 실인물이나 특정 유명인을 참조하지 않았으며 실제 개인정보·실고객 자료를 사용하지 않았다.
- 구도: 중앙 정면의 머리·상반신, 회색 배경·부드러운 조명·일반 업무 복장. 역할이나 재직 상태를 외모로 표현하지 않는다.
- 원본: built-in 기본 저장 위치의 PNG를 유지하고 `target/demo-portraits/*-original.png`에 복사했다. 원본은 커밋 대상이 아니다.
- 앱용 인코딩: Windows 기본 System.Drawing으로 원래 구도를 유지해 640×640 RGB JPEG로 단순 축소·재인코딩했다. HighQualityBicubic, JPEG quality 90; 인물·배경에 대한 창작 편집은 하지 않았다.
- 검증: 생성 원본 4장을 `view_image`로 확인했다. 최종 JPEG 4장도 직접 표시해 검수하고 실제 디코딩·바이트·크기·픽셀 수·SHA-256을 확인했다. 각 파일은 128KiB 이하, 변 2048px 이하, 총 400만 픽셀 이하이다.
- 이 파일과 사진 자산만 작성했으며 서버·DB 변경이나 Git 커밋·Push는 수행하지 않았다. 실제 등록·헤더 확인은 별도 작업 결과를 따른다.

## 최종 JPEG

| 자산 | 경로 | 바이트 | 해상도 | SHA-256 |
| --- | --- | ---: | --- | --- |
| local-demo | [photos/local-demo.jpg](photos/local-demo.jpg) | 50139 | 640×640 | `fe1080eb46fa49f067b75cd910e138abeeca3c8ce71bae1e0f3ae49be662edb3` |
| demo-001 | [photos/demo-001.jpg](photos/demo-001.jpg) | 52157 | 640×640 | `ab9c4144ec1e860a6a7c8f0dc04df66fa7c50bec893d27fb33e33306bdce26c6` |
| demo-002 | [photos/demo-002.jpg](photos/demo-002.jpg) | 58372 | 640×640 | `c534041f9b51f3e817323abdd941b90b90f0ed995c64672b41d65ba04aa7650a` |
| demo-003 | [photos/demo-003.jpg](photos/demo-003.jpg) | 50722 | 640×640 | `1ba7f663638149ac36defd37c6943b009e8b32024e9894abec73189bc7b033cb` |

## 보존 원본

| 자산 | 프로젝트 내 보존 경로 | 바이트 | SHA-256 |
| --- | --- | ---: | --- |
| local-demo | `target/demo-portraits/local-demo-original.png` | 2113496 | `8abab5606b3b022b7d4cc54566b4a6d7519953f61d31d9b4fd956dc5f3b9d98f` |
| demo-001 | `target/demo-portraits/demo-001-original.png` | 2052777 | `bf7a5e34215eda5965e6d901b35fd84a369c410dc3b14940df1cb428c0ee49cd` |
| demo-002 | `target/demo-portraits/demo-002-original.png` | 2332589 | `fe59907dbae558ba8e0ebfb72b8dc877892fee0a9a84ef16aac7eef67ea5d280` |
| demo-003 | `target/demo-portraits/demo-003-original.png` | 2094678 | `0947981c3a8142ac26002915a8a1f42336e736f995f97eb93354523edbf30444` |

## 최종 입력 프롬프트

### local-demo

```text
Use case: photorealistic-natural
Asset type: Small HR application employee profile avatar, one individual studio portrait.
Primary request: Create a natural photorealistic studio profile headshot of an entirely fictional adult Korean professional.
Scene/backdrop: Plain soft cool-gray studio background.
Subject: An entirely fictional Korean man in his 40s, short black hair, wearing a navy jacket over a white shirt.
Composition/framing: Square individual portrait, head and upper chest centered, facing camera, generous headroom so the complete head remains visible in a small circular HR avatar crop.
Lighting/mood: Soft natural studio light, calm approachable expression, natural skin texture, consistent clean professional portrait set.
Constraints: Pure single-person photograph. The person must be entirely AI fictional, never reference or resemble any named real person. Clothing only as described. Do not encode any job role or employment status through appearance.
Avoid: Text, logos, badges, watermark, collage, multiple people, props, decorative background, overly retouched plastic skin.
```

### demo-001

```text
Use case: photorealistic-natural
Asset type: Small HR application employee profile avatar, one individual studio portrait.
Primary request: Create a natural photorealistic studio profile headshot of an entirely fictional adult Korean professional.
Scene/backdrop: Plain soft cool-gray studio background.
Subject: An entirely fictional Korean woman in her 30s, shoulder-length black hair, wearing a cream blouse.
Composition/framing: Square individual portrait, head and upper chest centered, facing camera, generous headroom so the complete head remains visible in a small circular HR avatar crop.
Lighting/mood: Soft natural studio light, calm approachable expression, natural skin texture, consistent clean professional portrait set.
Constraints: Pure single-person photograph. The person must be entirely AI fictional, never reference or resemble any named real person. Clothing only as described. Do not encode any job role or employment status through appearance.
Avoid: Text, logos, badges, watermark, collage, multiple people, props, decorative background, overly retouched plastic skin.
```

### demo-002

```text
Use case: photorealistic-natural
Asset type: Small HR application employee profile avatar, one individual studio portrait.
Primary request: Create a natural photorealistic studio profile headshot of an entirely fictional adult Korean professional.
Scene/backdrop: Plain soft cool-gray studio background.
Subject: An entirely fictional Korean man in his late 30s, neatly styled black hair, small understated eyeglasses, wearing a charcoal jacket.
Composition/framing: Square individual portrait, head and upper chest centered, facing camera, generous headroom so the complete head remains visible in a small circular HR avatar crop.
Lighting/mood: Soft natural studio light, calm approachable expression, natural skin texture, consistent clean professional portrait set.
Constraints: Pure single-person photograph. The person must be entirely AI fictional, never reference or resemble any named real person. Clothing only as described. Do not encode any job role or employment status through appearance.
Avoid: Text, logos, badges, watermark, collage, multiple people, props, decorative background, overly retouched plastic skin.
```

### demo-003

```text
Use case: photorealistic-natural
Asset type: Small HR application employee profile avatar, one individual studio portrait.
Primary request: Create a natural photorealistic studio profile headshot of an entirely fictional adult Korean professional.
Scene/backdrop: Plain soft cool-gray studio background.
Subject: An entirely fictional Korean woman in her 30s, neat short bob haircut, wearing a light blue shirt.
Composition/framing: Square individual portrait, head and upper chest centered, facing camera, generous headroom so the complete head remains visible in a small circular HR avatar crop.
Lighting/mood: Soft natural studio light, calm approachable expression, natural skin texture, consistent clean professional portrait set.
Constraints: Pure single-person photograph. The person must be entirely AI fictional, never reference or resemble any named real person. Clothing only as described. Do not encode any job role or employment status through appearance.
Avoid: Text, logos, badges, watermark, collage, multiple people, props, decorative background, overly retouched plastic skin.
```

## built-in 원본 위치

- local-demo: `C:/Users/ihpark/.codex/generated_images/01a19b74-f205-76c1-af0b-6b0bfb175bf4/exec-1d43850a-a552-46b8-980c-3f1f851f41a4.png`
- demo-001: `C:/Users/ihpark/.codex/generated_images/01a19b74-f205-76c1-af0b-6b0bfb175bf4/exec-2ecbcb94-be4b-48f7-87ba-9cc6f373a9c1.png`
- demo-002: `C:/Users/ihpark/.codex/generated_images/01a19b74-f205-76c1-af0b-6b0bfb175bf4/exec-0911bca6-a51e-408c-bd44-6fb21df9d4af.png`
- demo-003: `C:/Users/ihpark/.codex/generated_images/01a19b74-f205-76c1-af0b-6b0bfb175bf4/exec-3b05a695-9230-49ad-ab55-b0586441c4e1.png`

