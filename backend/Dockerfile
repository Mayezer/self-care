FROM eclipse-temurin:25.0.5_8-jdk-alpine@sha256:c7c3d0bdf7f2bbc5a05a6712ed334d198360105634ea9adf76fca94f381b1e3f AS build
WORKDIR /app
COPY --chmod=755 gradlew ./
COPY gradle/ ./gradle/
COPY build.gradle settings.gradle ./
COPY src/main/ ./src/main/
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25.0.5_8-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
RUN apk upgrade --no-cache
WORKDIR /app
COPY --from=build /app/build/libs/autocuidado.jar ./autocuidado.jar
USER 10001:10001
CMD ["java", "-jar", "autocuidado.jar"]
