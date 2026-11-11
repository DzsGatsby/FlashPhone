<!-- translated-from: README.md @ bd8976dbee3c -->

<div align="center">
  <img src="../../app/src/main/res/drawable-nodpi/flash_phone_launcher.png" width="104" alt="Flash phone 아이콘" />
  <h1>Flash phone</h1>
  <p>기존 SIM으로 전화를 받고 거는 Android AI 도우미입니다.</p>
  <p>
    <a href="https://github.com/DzsGatsby/FlashPhone/releases/latest"><strong>APK 다운로드</strong></a> ·
    <a href="../README.en.md">사용 안내(영어)</a> ·
    <a href="https://github.com/DzsGatsby/FlashPhone/issues/new/choose">문제 신고</a>
  </p>
</div>

[English](../../README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · 한국어 · [Español](README.es.md)

## 주요 기능

- **수신 전화 대신 받기.** AI가 응답할 전화를 정하고, 중요한 번호는 직접 받거나 자동 응답을 일시 중지할 수 있습니다. 전화가 끊기지 않은 채로 통화를 넘겨받을 수 있습니다.
- **목적에 맞게 전화 걸기.** 번호와 SIM을 고른 뒤 물어볼 내용, 배경과 약속해도 되는 범위를 입력하고 확인한 뒤 전화를 겁니다.
- **화면이 잠긴 상태에서 사용하기.** 통화 오디오 도우미와 백그라운드 대기가 준비되면 조건에 맞는 수신 전화에 응답할 수 있습니다. 확인한 발신 작업은 45초 이내에 화면을 잠그면 한 번만 발신을 시도하도록 설정할 수 있습니다.
- **통화 내용 확인하기.** 통화 텍스트와 원래 작업을 함께 살펴보고, 기기의 2채널 녹음을 재생하거나 처리 완료로 표시하고 새 초안으로 재사용할 수 있습니다.

## 설치 및 시작

1. Android 12 이상과 활성 SIM이 필요합니다. [GitHub 최신 릴리스](https://github.com/DzsGatsby/FlashPhone/releases/latest)에서 `FlashPhone-vX.Y.Z.apk`를 다운로드하고 Android 안내에 따라 설치합니다. 메시지가 표시되면 APK를 연 앱에 설치 권한을 부여합니다.
2. 휴대전화에서 통화 오디오 도우미를 무선 페어링하고 Volcengine 실시간 음성 서비스의 인증 정보를 입력합니다. 오디오 지원은 기기와 Android 빌드에 따라 다릅니다.
3. 양쪽에서 소리가 들리고 직접 통화를 넘겨받을 수 있는지 확인한 뒤 자동 응답을 켭니다. 오디오 확인 후 활성화하면 백그라운드 대기도 자동 시작되며, 자동 응답을 끄면 대기도 해제됩니다. [설정과 호환성 안내(영어)](../README.en.md)를 참조하세요.

이미 설치했다면 **설정 → 정보 → 업데이트 확인**을 사용하거나 서명된 APK를 기존 앱 위에 설치하면 설정과 통화 기록을 유지할 수 있습니다. [업데이트와 이전 안내(중국어 간체)](../app-updates.md)를 참조하세요.

## 서비스 및 데이터

Flash phone은 기존 SIM을 사용하며 평소 전화 앱을 계속 이용할 수 있습니다. AI가 전화를 처리하는 동안 음성은 설정된 음성 서비스로 전송되며, 기기에도 자동으로 2채널 녹음됩니다. 왼쪽은 수집한 상대 음성, 오른쪽은 기기의 전화 오디오 트랙이 수락한 AI 음성입니다. 직접 통화를 넘겨받으면 녹음이 중단됩니다. 녹음과 텍스트는 기기에 암호화해 저장하고, 녹음 재생 시 업로드하지 않습니다. 끼어들기로 대기 중인 오디오가 버려질 수 있어 오른쪽 채널만으로 상대가 실제로 들었다고 확인할 수 없습니다. 통신사와 음성 서비스에서 각각 요금이 발생할 수 있습니다.

녹음은 각각 최대 600초, 최대 50개, 총 200 MiB로 제한하며 필요하면 가장 오래된 것부터 삭제합니다. 페어링과 기기가 허용하는 권한 설정 후, 연결된 신뢰할 수 있는 Wi-Fi에서 무선 디버깅 복구를 시도하고 부팅·잠금 해제·업데이트 후에는 이전에 승인한 수신 대기 복구를 시도합니다. 시스템 신뢰 확인, 강제 종료, 제조사 제한을 우회하지 않으며 root 권한을 얻지 않습니다. 새로운 녹음과 복구 경로는 오프라인 검사를 마쳤지만 실제 전화 녹음과 재부팅·업데이트 후 복구는 기기 검증이 필요합니다.

## 프로젝트 및 라이선스

Flash phone은 [wangzan101과 기여자들이 만든 Mika](https://github.com/wangzan101/Mika)를 바탕으로 한 독립 프로젝트입니다. [Apache-2.0](../../LICENSE), [원본 저작권 고지](../../NOTICE), [타사 고지](../../licenses/THIRD_PARTY_NOTICES.md), [기여 안내(영어)](../../.github/CONTRIBUTING.md)를 참조하세요.
