plugins {
    // JDK 25가 로컬에 없으면 Gradle이 자동으로 내려받게 해 주는 플러그인
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "factory-edge-monitor"

include(
    "common",    // 공통 메시지 모델 (의존성 없음)
    "emulator",  // 설비 1,000대 모사 + 장애 주입
    "gateway",   // 공장당 1대, 수집·정규화·디스크 버퍼·재전송
    "server",    // 중앙 서버 (Spring Boot, 헥사고날)
)
