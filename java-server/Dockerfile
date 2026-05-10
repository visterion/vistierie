# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY java-server/.mvn/ .mvn/
COPY java-server/mvnw java-server/pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY java-server/src ./src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/vistierie-*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
