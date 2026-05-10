FROM maven:3.9-eclipse-temurin-26 AS build

WORKDIR /workspace
COPY . .

RUN mvn -ntp -DskipTests -Dskip.npm -Dskip.installnodenpm package \
    && JAR_FILE="$(ls target/*.jar | grep -Ev '(original|plain)' | head -n 1)" \
    && cp "$JAR_FILE" /tmp/app.jar

FROM eclipse-temurin:26-jre-noble

WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar

EXPOSE 5505
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
