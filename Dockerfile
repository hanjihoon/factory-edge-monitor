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
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q :${MODULE}:installDist -x test

FROM eclipse-temurin:25-jre
ARG MODULE
ENV MODULE=${MODULE}
WORKDIR /app
COPY --from=build /src/${MODULE}/build/install/${MODULE} /app
ENTRYPOINT ["sh", "-c", "exec /app/bin/${MODULE}"]
