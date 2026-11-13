FROM eclipse-temurin:25.0.4.1_1-jdk-alpine AS build
WORKDIR /app
COPY --chmod=755 gradlew ./
COPY gradle/ ./gradle/
COPY build.gradle settings.gradle ./
COPY src/main/ ./src/main/
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25.0.4.1_1-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048
RUN apk upgrade --no-cache
WORKDIR /app
COPY --from=build /app/build/libs/autocuidado.jar ./autocuidado.jar
USER 10001:10001
CMD ["java", "-jar", "autocuidado.jar"]
