FROM python:3.12-slim

# ffmpeg: yt-dlp's audio extraction (container change only, no re-encode).
# Deno: the JavaScript runtime yt-dlp needs for YouTube since 2025.11.12.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh -s -- -y \
 && deno --version

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt \
 && yt-dlp --version && beet --version

COPY bitchord_companion /app/bitchord_companion
WORKDIR /app

# Runs as whatever user docker-compose says (`user: "1000:1000"`), so the
# files it writes into the library belong to the same user as the rest.
ENV PYTHONUNBUFFERED=1 \
    HOME=/tmp \
    XDG_CACHE_HOME=/tmp/.cache \
    CONFIG_DIR=/config \
    MUSIC_DIR=/music \
    PORT=8765

EXPOSE 8765
ENTRYPOINT ["python", "-m", "bitchord_companion"]
