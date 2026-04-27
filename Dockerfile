# ── Test stage (docker compose run test) ──────────────────────────────────────
FROM azul/zulu-openjdk:25 AS test

RUN apt-get update && apt-get install -y --no-install-recommends \
        maven wget gnupg2 ca-certificates fonts-liberation && \
    wget -q -O - https://dl.google.com/linux/linux_signing_key.pub \
        | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg && \
    echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] \
        http://dl.google.com/linux/chrome/deb/ stable main" \
        > /etc/apt/sources.list.d/google-chrome.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends google-chrome-stable && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline --batch-mode --no-transfer-progress || true

COPY src src/
COPY licenses licenses/
COPY LICENSE NOTICE ./

ENTRYPOINT ["mvn"]
CMD ["clean", "verify", "--batch-mode", "--no-transfer-progress"]

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

LABEL org.opencontainers.image.source="https://github.com/tombueng/pdfalyzer-studio"
LABEL org.opencontainers.image.description="PDFalyzer Studio — PDF inspection web application"

RUN groupadd --system app && useradd --system --gid app app

WORKDIR /app
COPY --from=build /app/target/pdfalyzer-studio-*.jar app.jar

EXPOSE 8080

USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
