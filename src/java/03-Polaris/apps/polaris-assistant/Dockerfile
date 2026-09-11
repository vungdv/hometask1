# ---------- Build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy parent and module POMs for layer caching
COPY pom.xml .
COPY modules/polaris-common/pom.xml modules/polaris-common/
COPY modules/polaris-catalog/pom.xml modules/polaris-catalog/
COPY modules/polaris-order/pom.xml modules/polaris-order/
COPY modules/polaris-mcp/pom.xml modules/polaris-mcp/
COPY apps/polaris-server/pom.xml apps/polaris-server/
COPY apps/polaris-assistant/pom.xml apps/polaris-assistant/

# Copy source trees
COPY modules ./modules
COPY apps/polaris-assistant ./apps/polaris-assistant
COPY apps/polaris-server ./apps/polaris-server

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
