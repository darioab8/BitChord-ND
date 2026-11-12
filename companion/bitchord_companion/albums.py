"""What a download request may contain, checked before anything runs.

The companion runs yt-dlp on the server, so it accepts YouTube only: video ids
of the documented shape, or links on YouTube's own hosts. It is not a general
"download this URL" service.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from urllib.parse import urlparse

VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
YOUTUBE_HOSTS = {"music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be"}
COVER_HOST_SUFFIXES = (".googleusercontent.com", ".ytimg.com", ".ggpht.com")
MAX_TRACKS = 200
MAX_TEXT = 300


class BadRequest(ValueError):
    pass


@dataclass
class Track:
    video_id: str
    title: str
    artist: str


@dataclass
class AlbumRequest:
    """One album to fetch. Either [tracks] (from the app) or [url] (pasted)."""

    title: str
    artist: str
    folder: str
    year: int | None = None
    cover_url: str | None = None
    browse_id: str | None = None
    url: str | None = None
    tracks: list[Track] = field(default_factory=list)

    def to_json(self) -> dict:
        return {
            "title": self.title,
            "artist": self.artist,
            "folder": self.folder,
            "year": self.year,
            "coverUrl": self.cover_url,
            "browseId": self.browse_id,
            "url": self.url,
            "tracks": [{"videoId": t.video_id, "title": t.title, "artist": t.artist} for t in self.tracks],
        }


def _text(value, name: str, required: bool = False) -> str:
    if value is None:
        value = ""
    if not isinstance(value, str):
        raise BadRequest(f"{name} must be text")
    value = value.strip()
    if required and not value:
        raise BadRequest(f"{name} is required")
    if len(value) > MAX_TEXT:
        raise BadRequest(f"{name} is too long")
    return value


def safe_folder(raw: str | None, default: str) -> str:
    """A relative folder under the music library, never outside it.

    Empty, ".", ".." and absolute parts are dropped, so "../../etc" is "etc"
    and "/YouTube/" is "YouTube". Nothing left means the default folder.
    """
    parts = []
    for part in re.split(r"[\\/]+", raw or ""):
        part = part.strip()
        if part in ("", ".", ".."):
            continue
        part = re.sub(r"[\x00-\x1f<>:\"|?*]", "_", part)
        parts.append(part)
    if not parts:
        return safe_folder(default, "") if default else ""
    return "/".join(parts)


def is_youtube_url(url: str) -> bool:
    try:
        u = urlparse(url)
    except ValueError:
        return False
    return u.scheme in ("http", "https") and (u.hostname or "").lower() in YOUTUBE_HOSTS


def is_cover_url(url: str) -> bool:
    try:
        u = urlparse(url)
    except ValueError:
        return False
    host = (u.hostname or "").lower()
    return u.scheme == "https" and any(host.endswith(s) for s in COVER_HOST_SUFFIXES)


def parse(body: dict, default_folder: str) -> AlbumRequest:
    if not isinstance(body, dict):
        raise BadRequest("Expected a JSON object")
    url = _text(body.get("url"), "url") or None
    raw_tracks = body.get("tracks") or []
    if not isinstance(raw_tracks, list):
        raise BadRequest("tracks must be a list")
    if not url and not raw_tracks:
        raise BadRequest("Send the album's tracks or a YouTube Music link")
    if url and not is_youtube_url(url):
        raise BadRequest("Only YouTube / YouTube Music links are accepted")
    if len(raw_tracks) > MAX_TRACKS:
        raise BadRequest(f"At most {MAX_TRACKS} tracks per album")
    tracks = []
    for i, t in enumerate(raw_tracks, 1):
        if not isinstance(t, dict):
            raise BadRequest(f"Track {i} must be an object")
        vid = _text(t.get("videoId"), f"track {i} videoId", required=True)
        if not VIDEO_ID.match(vid):
            raise BadRequest(f"Track {i} has an invalid videoId")
        tracks.append(Track(vid, _text(t.get("title"), f"track {i} title"), _text(t.get("artist"), f"track {i} artist")))
    year = body.get("year")
    if year is not None and (not isinstance(year, int) or not 1000 <= year <= 3000):
        year = None
    cover = _text(body.get("coverUrl"), "coverUrl") or None
    if cover and not is_cover_url(cover):
        cover = None
    return AlbumRequest(
        title=_text(body.get("title"), "title", required=not url),
        artist=_text(body.get("artist"), "artist"),
        folder=safe_folder(_text(body.get("folder"), "folder"), default_folder),
        year=year,
        cover_url=cover,
        browse_id=_text(body.get("browseId"), "browseId") or None,
        url=url,
        tracks=tracks,
    )


def from_stored(d: dict) -> AlbumRequest:
    """Reads back what [AlbumRequest.to_json] stored. Trusted: it was validated on the way in."""
    return AlbumRequest(
        title=d.get("title") or "",
        artist=d.get("artist") or "",
        folder=d.get("folder") or "",
        year=d.get("year"),
        cover_url=d.get("coverUrl"),
        browse_id=d.get("browseId"),
        url=d.get("url"),
        tracks=[Track(t["videoId"], t.get("title") or "", t.get("artist") or "") for t in d.get("tracks") or []],
    )
