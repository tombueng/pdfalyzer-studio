# Pinned Maven for the in-image builds. The distro's apt Maven is < 3.9, which
# license-maven-plugin 2.9.0 rejects (it requires Maven >= 3.9.0), so install a
# verified Apache Maven instead of `apt-get install maven`.
ARG MAVEN_VERSION=3.9.16

# ── Maven install (shared) ────────────────────────────────────────────────────
FROM azul/zulu-openjdk:27 AS maven-base
ARG MAVEN_VERSION

RUN apt-get update && apt-get install -y --no-install-recommends wget ca-certificates && \
    wget -q "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -O /tmp/maven.tar.gz && \
    wget -q "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz.sha512" -O /tmp/maven.tar.gz.sha512 && \
    echo "$(cat /tmp/maven.tar.gz.sha512)  /tmp/maven.tar.gz" | sha512sum -c - && \
    mkdir -p /opt/maven && \
    tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1 && \
    rm /tmp/maven.tar.gz /tmp/maven.tar.gz.sha512 && \
    rm -rf /var/lib/apt/lists/*

# ── Test stage (docker compose run test) ──────────────────────────────────────
FROM azul/zulu-openjdk:27 AS test

COPY --from=maven-base /opt/maven /opt/maven
ENV PATH="/opt/maven/bin:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
        wget gnupg2 ca-certificates fonts-liberation && \
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
RUN mvn dependency:go-offline --batch-mode --no-transfer-progress || true

COPY src src/
COPY licenses licenses/
COPY LICENSE NOTICE ./

ENTRYPOINT ["mvn"]
CMD ["clean", "verify", "--batch-mode", "--no-transfer-progress"]

# ── Build stage ────────────────────────────────────────────────────────────────
FROM azul/zulu-openjdk:27 AS build

COPY --from=maven-base /opt/maven /opt/maven
ENV PATH="/opt/maven/bin:${PATH}"

WORKDIR /app
COPY pom.xml .
COPY src src/
COPY licenses licenses/
COPY LICENSE NOTICE ./

RUN mvn clean package -DskipTests --batch-mode --no-transfer-progress

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM azul/zulu-openjdk:27-jre

LABEL org.opencontainers.image.source="https://github.com/tombueng/pdfalyzer-studio"
LABEL org.opencontainers.image.description="PDFalyzer Studio — PDF inspection web application"

RUN groupadd --system app && useradd --system --gid app app

WORKDIR /app
COPY --from=build /app/target/pdfalyzer-studio-*.jar app.jar

EXPOSE 8080

USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
