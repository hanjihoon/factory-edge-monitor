plugins {
    application
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":common"))

    // Spring Boot 4는 스타터가 모듈 단위로 쪼개졌다. web → webmvc, flyway도 전용 스타터가 필요하다.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 종단 테스트: 실제 게이트웨이 전송 코드(Uplink, WebSocketConnector, SqliteOutbox)를 서버에 붙여 본다
    testImplementation(project(":gateway"))
    // 저장소 테스트: 메모리 가짜가 아니라 운영과 같은 TimescaleDB 이미지에서 실제 SQL을 돌린다 (버전은 Boot BOM이 관리)
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 네 모듈 모두 installDist로 같은 형태(bin/ + lib/)로 패키징해서 Dockerfile 하나로 처리한다.
application { mainClass.set("fem.server.ServerApplication") }

// 게이트웨이 SQLite outbox를 테스트에서 쓰므로 네이티브 접근 허용 (JDK 25 경고 방지)
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
