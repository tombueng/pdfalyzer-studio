# ── Build stage ────────────────────────────────────────────────────────────────
FROM azul/zulu-openjdk:25 AS build

WORKDIR /app
COPY pom.xml .
COPY src src/
COPY licenses licenses/
COPY LICENSE NOTICE ./

RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y --no-install-recommends maven && \
    mvn clean package -DskipTests --batch-mode --no-transfer-progress

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM azul/zulu-openjdk:25-jre

LABEL org.opencontainers.image.source="https://github.com/tombueng/pdfalyzer-ui"
LABEL org.opencontainers.image.description="PDFalyzer Studio — PDF inspection web application"

RUN groupadd --system app && useradd --system --gid app app

WORKDIR /app
COPY --from=build /app/target/pdfalyzer-ui-*.jar app.jar

EXPOSE 8080

USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
