# BitChord ND companion

A small service that runs next to Navidrome. When you tap **Download to server**
on a YouTube Music album in BitChord ND, it:

1. downloads every track with **yt-dlp** at YouTube's best audio quality, without
   re-encoding (Opus ~130–160 kbps, or 256 kbps AAC with a Premium session);
2. writes title / artist / album / track-number tags;
3. imports the album with **beets** (MusicBrainz matching, cover art), marked
   `source=youtube`, into a folder of your library;
4. optionally asks **Navidrome** to rescan.

Albums only, one at a time, in the order you asked for them. It also has a web
page for trying it from a browser: paste an album link and watch the queue.

> Downloading from YouTube is against YouTube's terms of service. This is for
> your own library; it accepts YouTube links only.

## Setup

1. **Pick a token.** `openssl rand -hex 24`
2. **Add the service** to the compose file Navidrome is in. Start from
   [`docker-compose.example.yml`](docker-compose.example.yml): mount the same
   music folder Navidrome uses at `/music`, and a folder for its own data at
   `/config`. Set `user:` to the owner of your music folder.
3. `docker compose up -d bitchord-companion`, then open
   `http://<server>:8765`, paste the token and try an album link
   (`https://music.youtube.com/playlist?list=OLAK5uy_…`).
4. In the app: **Settings → Download to server**: the address
   (`http://<server>:8765`), the token, and the folder albums should go to.

Keep it reachable on your LAN or VPN only, like Navidrome itself.

## Options

| Variable | Default | |
|---|---|---|
| `COMPANION_TOKEN` | — | Required, 16+ characters. |
| `DEFAULT_FOLDER` | `YouTube` | Folder under `/music` used when the app doesn't name one. Empty = library root. |
| `NAVIDROME_URL`, `NAVIDROME_USER`, `NAVIDROME_PASSWORD` | — | Rescan right after an import (admin user). Without them Navidrome picks albums up on its own scan or folder watcher. |
| `BEETS_CONFIG` | generated | Use your own beets config. |
| `COOKIES_FILE` | `/config/cookies.txt` | See below. |

**Premium quality.** Export your `music.youtube.com` cookies in Netscape format
(a "cookies.txt" browser extension does it) to `/config/cookies.txt`. With a
Premium account yt-dlp then gets 256 kbps AAC where YouTube offers it. Without
the file it uses Opus. The web page shows which one is active.

**beets.** On first start the companion writes `/config/beets/config.yaml`:
quiet imports, falling back to the downloaded tags when MusicBrainz isn't sure,
skipping albums beets already has, `$albumartist/$album/$track $title` paths,
cover art fetched and embedded. It's only written when missing, so edit it
freely. Its library (`/config/beets/library.db`) is separate from any beets you
already run. Every album it imports has `source=youtube` (and
`youtube_album=<id>`), so `beet ls source:youtube` lists them — handy when you
replace one with a FLAC rip later.

## API

All `/api` calls need `Authorization: Bearer <token>`.

| | |
|---|---|
| `GET /api/health` | versions, cookies on/off, default folder |
| `GET /api/jobs` | recent jobs |
| `POST /api/jobs` | `{title, artist, year?, coverUrl?, browseId?, folder?, tracks: [{videoId, title, artist}]}` or `{url, folder?}` |
| `GET /api/jobs/<id>` | one job: `state` is queued, downloading, tagging, scanning, done, skipped, failed or cancelled |
| `POST /api/jobs/<id>/cancel` | |
| `DELETE /api/jobs/<id>` | forget a finished job |

## Development

```sh
cd companion
python -m unittest discover -s tests
```

The tests use fakes for yt-dlp, beets and Navidrome, so they need neither.
