// 공통 모델은 의존성을 두지 않는다.
// 게이트웨이(엣지)와 서버가 같은 계약을 쓰되, 어느 쪽 프레임워크에도 묶이지 않게 하기 위해서다.
// (테스트 의존성은 배포물에 들어가지 않으므로 예외)
plugins { `java-library` }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
