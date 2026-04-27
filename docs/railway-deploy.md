# Deploying to Railway

PDFalyzer Studio is configured to deploy on [Railway](https://railway.com) using the project's `Dockerfile`. Configuration lives in [`railway.toml`](../railway.toml).

## What's already set up in this repo

- `Dockerfile` — multi-stage build, runtime image based on `azul/zulu-openjdk:25-jre`, runs as non-root user.
- `railway.toml` — tells Railway to build via Dockerfile and use `/actuator/health` as the healthcheck.
- `application.properties` — `server.port=${PORT:8080}` so Spring Boot binds to whatever port Railway injects.

## One-time setup in Railway

1. **Sign in** at <https://railway.com> and open your project (or create one).
2. **New service → Deploy from GitHub repo → `tombueng/pdfalyzer-ui`**.
   - Railway requests GitHub access; grant it for this repo.
   - Auto-detects `railway.toml` and builds from `Dockerfile`.
3. **Generate a public domain** under the service's **Settings → Networking → Generate Domain**. Railway issues a `*.up.railway.app` URL and routes external traffic to `$PORT`.
4. **Resource sizing** under **Settings → Resources**:
   - Memory: **at least 1 GB** (Spring Boot + PDFBox + JVM overhead).
   - vCPU: 1 is fine for low traffic.
5. **Environment variables** under **Variables** — none required for a basic deploy. Optional:
   - `JAVA_OPTS=-Xmx768m` if you hit memory limits on a 1 GB plan.
   - `SPRING_PROFILES_ACTIVE=prod` if you add a production profile later.
6. **Deploy.** Railway pulls `main`, builds the Dockerfile, and rolls out. ~5–8 min for a cold build, ~2 min after Docker layer caching kicks in.

## Auto-deploy on push

Once connected, Railway redeploys automatically on every push to `main`. No GitHub Actions changes needed — Railway watches the branch directly.

To deploy a specific commit or branch instead, change **Settings → Source → Branch** in Railway.

## Verifying a deploy

After the first deploy:

```bash
curl https://<your-domain>.up.railway.app/actuator/health
# {"status":"UP"}
```

The Railway dashboard shows build logs, runtime logs, and metrics under the service's **Deployments** and **Logs** tabs.

## Switching to image-based deploy (optional)

If you prefer Railway to pull the prebuilt image from GHCR (which CI publishes on every main push) instead of rebuilding:

1. In Railway: **New service → Deploy a Docker image**.
2. Image: `ghcr.io/tombueng/pdfalyzer-ui:latest`.
3. If the GitHub package is private, add a registry credential under **Settings → Image Registry**.
4. Railway redeploys when you push the same tag — trigger via the dashboard or use a Railway redeploy webhook from CI.

This skips Railway's build step (~5 min faster per deploy) but requires CI to keep pushing `:latest`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Healthcheck fails, container restarts in a loop | Increase `healthcheckTimeout` in `railway.toml` (Spring Boot cold start can exceed 60 s on small instances). |
| `OutOfMemoryError` in logs | Bump memory to 2 GB, or add `JAVA_OPTS=-Xmx<limit>m` env var. |
| Build OOM during `mvn package` | Bump build resources under **Settings → Build**, or switch to image-based deploy from GHCR. |
| Port binding errors | Confirm `server.port=${PORT:8080}` is in `application.properties` — Railway injects `$PORT` per deploy. |
