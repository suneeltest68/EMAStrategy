FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app
COPY . .
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/install/cww /app/cww
COPY --from=builder /app/libs /app/libs

CMD ["/app/cww/bin/cww"]
