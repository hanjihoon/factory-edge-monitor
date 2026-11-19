# 모듈 하나를 빌드해서 실행하는 공용 Dockerfile. docker compose에서 MODULE 인자로 구분한다.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# 의존성 캐시가 잘 먹도록 빌드 스크립트를 먼저 복사
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY common common
COPY emulator emulator
COPY gateway gateway
COPY server server
ARG MODULE
# sharing=locked: compose가 server·emulator·gateway 이미지를 동시에 빌드하면 여러 컨테이너의 Gradle이
# 같은 캐시(/root/.gradle)의 잠금을 두고 다툰다. Gradle은 잠금 해제 요청을 같은 호스트의 다른 프로세스에
# 보내는데, 컨테이너가 달라 요청이 닿지 않아 "Timeout waiting to lock journal cache"로 실패한다.
# locked로 두면 BuildKit이 이 캐시를 쓰는 RUN을 한 번에 하나씩 실행해 충돌이 없다 (캐시는 그대로 재사용).
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon -q :${MODULE}:installDist -x test

FROM eclipse-temurin:25-jre
ARG MODULE
ENV MODULE=${MODULE}
WORKDIR /app
COPY --from=build /src/${MODULE}/build/install/${MODULE} /app
ENTRYPOINT ["sh", "-c", "exec /app/bin/${MODULE}"]
