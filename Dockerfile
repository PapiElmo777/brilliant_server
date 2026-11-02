FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp verify

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 brilliant && useradd --uid 10001 --gid brilliant --create-home brilliant \
    && mkdir /data /app && chown brilliant:brilliant /data /app
WORKDIR /app
COPY --from=build /build/target/brilliant-server.jar /app/brilliant-server.jar
USER 10001:10001
ENV DATA_DIR=/data BIND_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/brilliant-server.jar"]
