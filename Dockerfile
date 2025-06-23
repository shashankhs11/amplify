# Use the official Eclipse Temurin JDK 21 base image
FROM eclipse-temurin:21-jdk-jammy

# Set working directory
WORKDIR /app

ENV JAVA_TOOL_OPTIONS="-Djava.util.logging.ConsoleHandler.level=ALL"
ENV GOOGLE_APPLICATION_CREDENTIALS=/app/credentials.json
COPY /credentials.json /app/credentials.json

# Copy your Spring Boot fat JAR
COPY /amplify.jar app.jar

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
