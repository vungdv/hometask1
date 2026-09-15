# ---------- Build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy parent and module POMs for layer caching
COPY pom.xml .
COPY libs/polaris-common/pom.xml libs/polaris-common/
COPY apps/polaris/pom.xml apps/polaris/
COPY apps/polaris-assistant/pom.xml apps/polaris-assistant/

# Copy source trees
COPY libs ./libs
COPY apps/polaris ./apps/polaris
COPY apps/polaris-assistant ./apps/polaris-assistant

RUN mvn clean package -pl apps/polaris-assistant -am -DskipTests -B

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

COPY --from=build /app/apps/polaris-assistant/target/*.jar app.jar

VOLUME /app/data

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
