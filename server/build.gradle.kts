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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 네 모듈 모두 installDist로 같은 형태(bin/ + lib/)로 패키징해서 Dockerfile 하나로 처리한다.
application { mainClass.set("fem.server.ServerApplication") }
