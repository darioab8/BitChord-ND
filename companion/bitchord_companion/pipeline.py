"""Turning one album request into tagged files in the library.

    yt-dlp (best audio, no re-encode) → basic tags → beets import → Navidrome rescan

Everything that touches the outside world goes through [Tools], so the tests
can run the whole pipeline with fakes.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import threading
import time
import urllib.request
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from . import albums, jobs
from .config import Config

AUDIO_EXTENSIONS = {".opus", ".m4a", ".mp3", ".ogg", ".webm", ".aac", ".flac"}

# 141: 256 kbps AAC, only with a Premium session's cookies.
# 251: Opus ~130-160 kbps, the best without Premium.
# Then anything audio-only, then anything at all.
FORMAT = "141/251/bestaudio[acodec=opus]/bestaudio/best"

ITEM = re.compile(r"\[download\] Downloading item (\d+) of (\d+)")

#: Written into every downloaded file's comment tag. Says where the file came
#: from, and lets the host importer find exactly this job's tracks in the beets
#: library afterwards (`beet ls comments:bitchord:<job id>`).
def marker(job_id: str) -> str:
    return f"BitChord ND · YouTube Music · bitchord:{job_id}"


#: Host mode: how long the host's beets may take over one album.
HOST_IMPORT_TIMEOUT = 60 * 60


class Cancelled(Exception):
    pass


class StepFailed(Exception):
    pass


class TracksFailed(StepFailed):
    """Some tracks didn't download. The ones that did are kept for a retry."""


@dataclass
class Result:
    state: str
    message: str


class Runner:
    """Runs a command, streaming its output, killable through [cancel]."""

    def run(self, cmd: list[str], on_line: Callable[[str], None], cancel: threading.Event) -> tuple[int, str]:
        tail: deque[str] = deque(maxlen=40)
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        watcher = threading.Thread(target=_kill_on_cancel, args=(proc, cancel), daemon=True)
        watcher.start()
        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.rstrip()
            tail.append(line)
            on_line(line)
        code = proc.wait()
        if cancel.is_set():
            raise Cancelled()
        return code, "\n".join(tail)


def _kill_on_cancel(proc: subprocess.Popen, cancel: threading.Event) -> None:
    while proc.poll() is None:
        if cancel.wait(0.5):
            proc.kill()
            return


@dataclass
class Tools:
    runner: Runner
    #: Writes title/artist/album/track tags into one file.
    tag: Callable[[Path, dict], None]
    #: Fetches a URL's bytes (cover art).
    fetch: Callable[[str], bytes]
    #: Asks Navidrome to rescan; returns an error message or None.
    rescan: Callable[[], str | None]


# ---- commands ------------------------------------------------------------------


def ytdlp_base(cfg: Config) -> list[str]:
    cmd = [
        "yt-dlp",
        "--format", FORMAT,
        "--extract-audio",  # no --audio-format: keep the codec, only change the container
        "--embed-metadata",
        "--no-mtime",
        "--newline",
        "--no-progress",
        "--retries", "5",
    ]
    if cfg.cookies:
        cmd += ["--cookies", str(cfg.cookies)]
    return cmd


def ytdlp_track(cfg: Config, video_id: str, number: int, out_dir: Path) -> list[str]:
    return ytdlp_base(cfg) + [
        "--no-playlist",
        "--output", str(out_dir / f"{number:02d}.%(ext)s"),
        f"https://music.youtube.com/watch?v={video_id}",
    ]


def ytdlp_album(cfg: Config, url: str, out_dir: Path) -> list[str]:
    return ytdlp_base(cfg) + [
        "--yes-playlist",
        "--parse-metadata", "playlist_index:%(track_number)s",
        "--output", str(out_dir / "%(playlist_index)02d.%(ext)s"),
        url,
    ]


def beets_import(cfg: Config, beets_config: Path, library_dir: Path, staging: Path, request: albums.AlbumRequest) -> list[str]:
    cmd = [
        "beet", "-c", str(beets_config), "-d", str(library_dir),
        "import", "--quiet",
        "--set", "source=youtube",
    ]
    if request.browse_id:
        cmd += ["--set", f"youtube_album={request.browse_id}"]
    return cmd + [str(staging)]


# ---- the pipeline ----------------------------------------------------------------


def run(job: jobs.Job, cfg: Config, beets_config: Path, store: jobs.JobStore, tools: Tools, cancel: threading.Event) -> Result:
    request = albums.from_stored(job.request)
    staging = cfg.work_dir / job.id
    # A retry carries on from the tracks it already has; anything else starts clean.
    resuming = any(t.get("state") in (jobs.TRACK_DONE, jobs.TRACK_SKIPPED) for t in job.tracks)
    if staging.exists() and not resuming:
        shutil.rmtree(staging)
    staging.mkdir(parents=True, exist_ok=True)
    keep_staging = False
    try:
        _download(job, request, cfg, staging, store, tools, cancel)
        files = _audio_files(staging)
        if not files:
            raise StepFailed("yt-dlp finished without any audio files")
        if request.tracks:
            _tag(request, files, tools, job.id)
        else:
            for f in files:
                try:
                    tools.tag(f, {"comments": marker(job.id)})
                except Exception:
                    pass
        if request.cover_url:
            try:
                (staging / "cover.jpg").write_bytes(tools.fetch(request.cover_url))
            except Exception:  # cover art is a nicety; beets' fetchart looks online too
                pass

        if cfg.host_import:
            return _host_import(job, request, cfg, staging, store, tools, cancel)

        store.update(job.id, state=jobs.TAGGING, message="Tagging with beets")
        library_dir = cfg.music_dir / request.folder if request.folder else cfg.music_dir
        library_dir.mkdir(parents=True, exist_ok=True)
        code, out = tools.runner.run(beets_import(cfg, beets_config, library_dir, staging, request), lambda _: None, cancel)
        if code != 0:
            raise StepFailed("beets failed:\n" + out)
        if _audio_files(staging):
            # Moved nothing: beets skipped the album (already in its library).
            return Result(jobs.SKIPPED, "Already in the library (beets skipped it)")

        if cfg.navidrome_configured:
            store.update(job.id, state=jobs.SCANNING, message="Asking Navidrome to rescan")
            problem = tools.rescan()
            if problem:
                return Result(jobs.DONE, f"In the library; Navidrome rescan failed: {problem}")
        return Result(jobs.DONE, "In the library")
    except Cancelled:
        keep_staging = bool(job.tracks)  # a retry can pick up from here
        return Result(jobs.CANCELLED, "Cancelled")
    except TracksFailed as e:
        keep_staging = True
        return Result(jobs.FAILED, str(e)[:4000])
    except StepFailed as e:
        return Result(jobs.FAILED, str(e)[-4000:])
    finally:
        if not keep_staging:
            shutil.rmtree(staging, ignore_errors=True)


def _track_file(staging: Path, number: int) -> Path | None:
    for f in staging.glob(f"{number:02d}.*"):
        if f.suffix.lower() in AUDIO_EXTENSIONS:
            return f
    return None


def _download(job, request, cfg, staging, store, tools, cancel) -> None:
    if request.tracks:
        tracks = job.tracks
        if not tracks:  # a job from before per-track state
            tracks = [
                {"number": n, "videoId": t.video_id, "title": t.title, "artist": t.artist,
                 "state": jobs.TRACK_WAITING, "error": ""}
                for n, t in enumerate(request.tracks, 1)
            ]
            store.update(job.id, tracks=tracks)
        total = len(tracks)
        wanted = [t for t in tracks if t.get("state") != jobs.TRACK_SKIPPED]
        done = 0
        failed = []
        for t in wanted:
            number = t["number"]
            if t.get("state") == jobs.TRACK_DONE and _track_file(staging, number):
                done += 1
                continue
            store.update_track(job.id, number, jobs.TRACK_DOWNLOADING)
            store.update(job.id, state=jobs.DOWNLOADING, progress=done, total=total,
                         message=f"Downloading {number} of {total}")
            code, out = tools.runner.run(ytdlp_track(cfg, t["videoId"], number, staging), lambda _: None, cancel)
            if code == 0 and _track_file(staging, number):
                store.update_track(job.id, number, jobs.TRACK_DONE)
                done += 1
            else:
                error = _last_error(out)
                store.update_track(job.id, number, jobs.TRACK_FAILED, error)
                failed.append((number, t.get("title") or t["videoId"], error))
        store.update(job.id, progress=done, total=total)
        if failed:
            first = f"{failed[0][0]}. {failed[0][1]}" if len(failed) == 1 else f"{len(failed)} tracks"
            lines = "\n".join(f"{n}. {title}: {err}" for n, title, err in failed)
            them = "it" if len(failed) == 1 else "them"
            raise TracksFailed(
                f"{first} didn't download ({len(failed)} of {total} failed). "
                f"Retry {them}, or import the album without {them}.\n{lines}"
            )
        return

    store.update(job.id, state=jobs.DOWNLOADING, message="Downloading")

    def progress(line: str) -> None:
        m = ITEM.search(line)
        if m:
            n, total = int(m.group(1)), int(m.group(2))
            store.update(job.id, progress=n - 1, total=total, message=f"Downloading {n} of {total}")

    code, out = tools.runner.run(ytdlp_album(cfg, request.url, staging), progress, cancel)
    if code != 0:
        raise StepFailed("yt-dlp failed:\n" + out)


def _tag(request: albums.AlbumRequest, files: list[Path], tools: Tools, job_id: str) -> None:
    total = len(request.tracks)
    by_number = {int(f.stem): f for f in files if f.stem.isdigit()}
    for number, track in enumerate(request.tracks, 1):
        f = by_number.get(number)
        if f is None:
            continue
        tags = {
            "title": track.title,
            "artist": track.artist or request.artist,
            "album": request.title,
            "albumartist": request.artist,
            "track": number,
            "tracktotal": total,
            "comments": marker(job_id),
        }
        if request.year:
            tags["year"] = request.year
        try:
            tools.tag(f, {k: v for k, v in tags.items() if v})
        except Exception:
            pass  # beets still matches on the audio files without these hints


# ---- host mode ----------------------------------------------------------------------


def album_folder_name(request: albums.AlbumRequest, job_id: str) -> str:
    """"Artist - Album (Year)", safe as one folder name."""
    name = " - ".join(p for p in (request.artist, request.title) if p) or f"YouTube album {job_id}"
    if request.year:
        name += f" ({request.year})"
    name = re.sub(r'[\x00-\x1f/\\<>:"|?*]', "_", name).strip(" .")
    return (name or f"YouTube album {job_id}")[:150]


def _unique(path: Path) -> Path:
    if not path.exists():
        return path
    n = 2
    while (candidate := path.with_name(f"{path.name} ({n})")).exists():
        n += 1
    return candidate


def parse_result(text: str) -> dict[str, str]:
    """The host importer's answer: `key=value` lines (code, imported, matched)."""
    out = {}
    for line in text.splitlines():
        key, sep, value = line.partition("=")
        if sep:
            out[key.strip()] = value.strip()
    return out


def _host_import(job, request, cfg: Config, staging: Path, store, tools: Tools, cancel: threading.Event) -> Result:
    """Hands the album to the host's beets and reports what it did.

    The album appears in the inbox in one rename (beets never sees half an
    album), then `<job>.request` in the queue names its folder. The host
    importer (companion/host/bitchord-import.sh) renames the request to
    `<job>.working` when it starts and writes `<job>.result` when it's done.
    """
    cfg.inbox_dir.mkdir(parents=True, exist_ok=True)
    cfg.queue_dir.mkdir(parents=True, exist_ok=True)
    final = _unique(cfg.inbox_dir / album_folder_name(request, job.id))
    hidden = cfg.inbox_dir / f".bitchord-{job.id}"  # beets ignores dot-folders
    if hidden.exists():
        shutil.rmtree(hidden)
    shutil.move(str(staging), str(hidden))
    hidden.rename(final)
    where = f"incoming_music/{final.name}"

    store.update(job.id, state=jobs.IMPORTING, message="Waiting for beets on the server")
    request_file = cfg.queue_dir / f"{job.id}.request"
    working = cfg.queue_dir / f"{job.id}.working"
    result_file = cfg.queue_dir / f"{job.id}.result"
    log_file = cfg.queue_dir / f"{job.id}.log"
    for stale in (working, result_file, log_file):
        stale.unlink(missing_ok=True)
    tmp = cfg.queue_dir / f".{job.id}.request"
    tmp.write_text(final.name + "\n")
    tmp.rename(request_file)

    started = time.monotonic()
    picked = False
    try:
        while not result_file.exists():
            if cancel.wait(1.0):
                if request_file.exists():
                    request_file.unlink(missing_ok=True)
                    return Result(jobs.CANCELLED, f"Cancelled before beets started. The album is in {where}")
                return Result(jobs.CANCELLED, f"Cancelled, but beets had already started on {where}")
            if not picked and (working.exists() or not request_file.exists()):
                picked = True
                store.update(job.id, message="beets is importing")
            waited = time.monotonic() - started
            if not picked and waited > cfg.host_pickup_timeout:
                request_file.unlink(missing_ok=True)
                raise StepFailed(
                    "beets on the server didn't pick the album up. Is the host importer set up "
                    f"(systemctl status bitchord-import.path)? The album is in {where}"
                )
            if waited > HOST_IMPORT_TIMEOUT:
                raise StepFailed(f"beets didn't finish within an hour. The album is in {where}")

        result = parse_result(result_file.read_text())
        log = log_file.read_text(errors="replace") if log_file.exists() else ""
        code = int(result.get("code", "1") or 1)
        imported = int(result.get("imported", "0") or 0)
        matched = result.get("matched", "") == "yes"
        if code != 0:
            raise StepFailed(f"beets failed (exit {code}). The album is still in {where}:\n{log[-3000:]}")
        if imported == 0:
            return Result(
                jobs.SKIPPED,
                f"beets didn't import it (already in your library, or skipped). It's in {where}",
            )
        message = "Imported by beets" if matched else "Imported as-is (no confident MusicBrainz match)"
        if cfg.navidrome_configured:
            store.update(job.id, state=jobs.SCANNING, message="Asking Navidrome to rescan")
            problem = tools.rescan()
            if problem:
                message += f"; Navidrome rescan failed: {problem}"
        return Result(jobs.DONE, message)
    finally:
        for f in (working, result_file, log_file):
            f.unlink(missing_ok=True)


def _audio_files(folder: Path) -> list[Path]:
    return sorted(p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in AUDIO_EXTENSIONS)


def _last_error(out: str) -> str:
    errors = [l for l in out.splitlines() if l.startswith("ERROR")]
    return (errors[-1] if errors else out.splitlines()[-1] if out else "unknown error")[:300]


# ---- the real tools ---------------------------------------------------------------


def mediafile_tag(path: Path, tags: dict) -> None:
    from mediafile import MediaFile  # comes with beets

    f = MediaFile(str(path))
    for key, value in tags.items():
        setattr(f, key, value)
    f.save()


def http_fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "bitchord-nd-companion"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read(20 * 1024 * 1024)
