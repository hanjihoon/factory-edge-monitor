// 에뮬레이터는 Spring 없이 순수 Java로 만든다. 1,000대를 가상 스레드로 띄우는 것이 전부라 프레임워크가 필요 없다.
plugins { application }

dependencies {
    implementation(project(":common"))
    implementation(libs.paho.mqtt)       // 공장 A: 설비가 MQTT로 발행
    implementation(libs.j2mod)           // 공장 B: 설비가 Modbus TCP slave로 레지스터 제공
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

application { mainClass.set("fem.emulator.EmulatorMain") }
