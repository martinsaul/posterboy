FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar posterboy.jar

# SMTP listener and the admin UI / config API.
EXPOSE 2525 8080

ENTRYPOINT ["java", "-jar", "posterboy.jar"]
