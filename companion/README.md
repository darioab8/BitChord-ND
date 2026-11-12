# BitChord ND companion

A small service that runs next to Navidrome. When you tap **Download to server**
on a YouTube Music album in BitChord ND, it downloads every track with
**yt-dlp** at YouTube's best audio quality, without re-encoding (Opus
~130–160 kbps, or 256 kbps AAC with a Premium session), tags the files, and has
**beets** import the album into your library.

Albums only, one at a time, in the order you asked for them. It also has a web
page (`http://<server>:8765`) for trying it from a browser and for reading the
full error when something fails; day to day, the app does everything.

> Downloading from YouTube is against YouTube's terms of service. This is for
> your own library; it accepts YouTube links only.

## Two ways to import

**`IMPORT_MODE=host` — your own beets (recommended if you already use beets).**
The companion puts each album in your incoming folder as
`Artist - Album (Year)/`, then asks a small systemd unit on the server to run
*your* `beet import` on that folder only — your config, your plugins, your
library, your paths. It never writes to the music folder itself. Whatever else
is in the incoming folder is left alone.

**`IMPORT_MODE=beets` (the default) — beets inside the container.** For a
library beets doesn't manage yet: the companion imports with its own beets into
`MUSIC_DIR/<folder>` and keeps its own beets library under `/config/beets`.

Either way, every downloaded track carries the comment
`BitChord ND · YouTube Music · bitchord:<job>`, so `beet ls comments:bitchord`
lists everything that came from YouTube — handy when you replace an album with
a FLAC rip later.

## Setup with your own beets (host mode)

The example assumes Navidrome's compose file is in `~/navidrome`, with `music/`
and `incoming_music/` next to it, and that beets runs as `dario`. Adjust the
paths and user in the files if yours differ.

1. **Compose.** Add the service from
   [`docker-compose.example.yml`](docker-compose.example.yml) (the host-mode
   variant), with `COMPANION_TOKEN` in a `.env` file next to it
   (`openssl rand -hex 24`). Create the data folder yourself first, so it's
   yours and not root's: `mkdir -p ~/navidrome/companion`.
2. **The importer**, from a checkout of this repository:

   ```sh
   sudo install -m 755 companion/host/bitchord-import.sh /usr/local/bin/bitchord-import
   mkdir -p ~/.config/bitchord-companion
   cp companion/host/beets-auto.yaml ~/.config/bitchord-companion/
   sudo cp companion/host/bitchord-import.service companion/host/bitchord-import.path /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now bitchord-import.path
   ```

   If `which beet` isn't `/usr/bin/beet` or `/usr/local/bin/beet` (a pip
   `--user` or pipx install), add `Environment=BEET=/full/path/to/beet` to
   `bitchord-import.service`.
3. `docker compose up -d bitchord-companion`, open `http://<server>:8765`, paste
   the token, and try an album link.

`beets-auto.yaml` holds the rules for these unattended imports and is loaded on
top of your normal config, for these imports only:

- beets applies a MusicBrainz match by itself when it's confident; the
  threshold is a little looser than beets' default (0.10 instead of 0.04), so a
  couple of differently spelled titles still match;
- if the release has tracks the download doesn't, or the other way round, the
  album goes in **as-is** with YouTube's tags;
- an album that's already in your library is skipped and left in the incoming
  folder for you.

The app tells you which of these happened. When beets fails, the job's message
(app and web page) has beets' output.

## Options

| Variable | Default | |
|---|---|---|
| `COMPANION_TOKEN` | — | Required, 16+ characters. |
| `IMPORT_MODE` | `beets` | `host` or `beets`, see above. |
| `INBOX_DIR` | `/incoming` | Host mode: where the incoming folder is mounted. |
| `HOST_PICKUP_TIMEOUT` | `120` | Host mode: seconds before "the importer didn't pick it up" is reported. |
| `MUSIC_DIR` | `/music` | beets mode: the library. |
| `DEFAULT_FOLDER` | `YouTube` | beets mode: folder under `/music` when the app doesn't name one. |
| `NAVIDROME_URL`, `NAVIDROME_USER`, `NAVIDROME_PASSWORD` | — | Rescan right after an import (admin user). Without them Navidrome picks albums up on its own scan or folder watcher. |
| `BEETS_CONFIG` | generated | beets mode: your own config for the container's beets. |
| `COOKIES_FILE` | `/config/cookies.txt` | See below. |

**Premium quality.** Export your `music.youtube.com` cookies in Netscape format
(a "cookies.txt" browser extension does it) to `/config/cookies.txt` (that's
`~/navidrome/companion/cookies.txt` in the example). With a Premium account
yt-dlp then gets 256 kbps AAC where YouTube offers it. Without the file it uses
Opus. The web page and the app's settings show which one is active.

**Image.** `ghcr.io/darioab8/bitchord-nd-companion`: `:latest` is built from
`main`, and each branch gets its own tag (for example
`:nd-phase8-server-downloads`). It's rebuilt weekly so yt-dlp keeps up with
YouTube — pull it now and then. New GitHub packages start out private: make it
public (GitHub → your profile → Packages → bitchord-nd-companion → Package
settings → Change visibility) or `docker login ghcr.io` first. Or build it:
`docker build -t bitchord-nd-companion companion/`.

Keep it reachable on your LAN or VPN only, like Navidrome itself.

## API

All `/api` calls need `Authorization: Bearer <token>`.

| | |
|---|---|
| `GET /api/health` | versions, import mode, cookies on/off, default folder |
| `GET /api/jobs` | recent jobs |
| `POST /api/jobs` | `{title, artist, year?, coverUrl?, browseId?, folder?, tracks: [{videoId, title, artist}]}` or `{url, folder?}` |
| `GET /api/jobs/<id>` | one job: `state` is queued, downloading, tagging, importing, scanning, done, skipped, failed or cancelled |
| `POST /api/jobs/<id>/cancel` | |
| `DELETE /api/jobs/<id>` | forget a finished job |

## Development

```sh
cd companion
python -m unittest discover -s tests
```

The tests use fakes for yt-dlp, beets and Navidrome; the host-mode tests run
the real `host/bitchord-import.sh` against a fake `beet`.
