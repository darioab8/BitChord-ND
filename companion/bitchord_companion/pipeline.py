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


class Cancelled(Exception):
    pass


class StepFailed(Exception):
    pass


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
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    try:
        _download(job, request, cfg, staging, store, tools, cancel)
        files = _audio_files(staging)
        if not files:
            raise StepFailed("yt-dlp finished without any audio files")
        if request.tracks:
            _tag(request, files, tools)
        if request.cover_url:
            try:
                (staging / "cover.jpg").write_bytes(tools.fetch(request.cover_url))
            except Exception:  # cover art is a nicety; beets' fetchart looks online too
                pass

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
        return Result(jobs.CANCELLED, "Cancelled")
    except StepFailed as e:
        return Result(jobs.FAILED, str(e)[-4000:])
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def _download(job, request, cfg, staging, store, tools, cancel) -> None:
    if request.tracks:
        total = len(request.tracks)
        failed = []
        for number, track in enumerate(request.tracks, 1):
            store.update(job.id, state=jobs.DOWNLOADING, progress=number - 1, total=total,
                         message=f"Downloading {number} of {total}")
            code, out = tools.runner.run(ytdlp_track(cfg, track.video_id, number, staging), lambda _: None, cancel)
            if code != 0:
                failed.append((number, track.title or track.video_id, _last_error(out)))
        if failed:
            lines = "\n".join(f"{n}. {t}: {err}" for n, t, err in failed)
            raise StepFailed(f"{len(failed)} of {total} tracks failed to download:\n{lines}")
        store.update(job.id, progress=total)
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


def _tag(request: albums.AlbumRequest, files: list[Path], tools: Tools) -> None:
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
        }
        if request.year:
            tags["year"] = request.year
        try:
            tools.tag(f, {k: v for k, v in tags.items() if v})
        except Exception:
            pass  # beets still matches on the audio files without these hints


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
