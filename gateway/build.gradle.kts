// 게이트웨이는 공장 현장에 놓이는 엣지 장비라고 가정한다.
// 자원이 작은 장비에서 돌 수 있도록 Spring 없이 순수 Java + 최소 라이브러리로 만든다.
// 서버와의 WebSocket은 JDK 내장 java.net.http.WebSocket을 쓰므로 의존성이 없다.
plugins { application }

dependencies {
    implementation(project(":common"))
    implementation(libs.paho.mqtt)       // 공장 A 브로커 구독
    implementation(libs.j2mod)           // 공장 B Modbus master (0.5초 폴링)
    implementation(libs.sqlite.jdbc)     // 단절 중 로컬 디스크 버퍼
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // 수집기 테스트에서 실제 에뮬레이터 Modbus 설비를 폴링해 본다 (테스트 전용)
    testImplementation(project(":emulator"))
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// sqlite-jdbc는 네이티브 라이브러리를 System.load로 올린다. JDK 25는 이를 경고하고 이후 버전에서 막을 예정이라 명시적으로 허용한다.
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

application {
    mainClass.set("fem.gateway.GatewayMain")
    applicationDefaultJvmArgs = listOf(nativeAccess)
}

tasks.withType<Test>().configureEach { jvmArgs(nativeAccess) }
