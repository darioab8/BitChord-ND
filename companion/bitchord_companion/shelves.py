"""Shelves: the owner's own lists of albums, like a Letterboxd list.

Kept here rather than on the phone so they survive a reinstall and follow the
account to another device. Navidrome has nothing like them, and a playlist
holds songs, not albums.

An album on a shelf is stored with what's needed to draw its card without
asking anyone: its browse id (a server album, `nd:album:<id>`, or a YouTube
Music one, `MPRE…`), title, artist, year, and its artwork. Server artwork is
kept as the cover-art id only: a Subsonic cover URL carries the login's token,
which has no business sitting in this database. The app builds the URL.

A shelf can have its own cover, uploaded from the app. The image is kept as a
file next to the database (`shelf-covers/<id>`) and the row only records its
version, which changes with every upload so caches never show an old one.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import sqlite3
import threading
import time
import uuid
from pathlib import Path

from .albums import is_cover_url

MAX_NAME = 100
MAX_DESCRIPTION = 2000
MAX_ALBUMS = 5000
MAX_SHELVES = 500
PREVIEW_COVERS = 4
MAX_COVER = 5 * 1024 * 1024

# What an uploaded cover may be, by its first bytes.
_IMAGE_TYPES = (
    (b"\xff\xd8\xff", "image/jpeg"),
    (b"\x89PNG\r\n\x1a\n", "image/png"),
)


def image_type(data: bytes) -> str | None:
    for magic, mime in _IMAGE_TYPES:
        if data.startswith(magic):
            return mime
    if len(data) > 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def cover_signature(token: str, shelf_id: str, version: str) -> str:
    """What a cover link carries instead of the token: images load without headers."""
    return hmac.new(token.encode(), f"{shelf_id}:{version}".encode(), hashlib.sha256).hexdigest()[:32]


class BadShelf(ValueError):
    pass


def _text(value, name: str, limit: int, required: bool = False) -> str:
    if value is None:
        value = ""
    if not isinstance(value, str):
        raise BadShelf(f"{name} must be text")
    value = value.strip()
    if required and not value:
        raise BadShelf(f"{name} is required")
    if len(value) > limit:
        raise BadShelf(f"{name} is longer than {limit} characters")
    return value


def parse_album(d) -> dict:
    """One album as the app sends it, cleaned. Raises BadShelf."""
    if not isinstance(d, dict):
        raise BadShelf("An album must be an object")
    album = {
        "id": _text(d.get("id"), "Album id", 300, required=True),
        "title": _text(d.get("title"), "Album title", 500, required=True),
        "artist": _text(d.get("artist"), "Album artist", 500),
    }
    year = d.get("year")
    if isinstance(year, int) and 0 < year < 10000:
        album["year"] = year
    cover = d.get("cover")
    if isinstance(cover, str) and cover and is_cover_url(cover):
        album["cover"] = cover
    cover_art = d.get("coverArt")
    if isinstance(cover_art, str) and cover_art.strip():
        album["coverArt"] = _text(cover_art, "Cover art id", 300)
    return album


class Shelf:
    def __init__(self, row: sqlite3.Row):
        self.id = row["id"]
        self.name = row["name"]
        self.description = row["description"]
        self.created = row["created"]
        self.updated = row["updated"]
        self.cover = row["cover"] if "cover" in row.keys() else None
        self.albums: list[dict] = json.loads(row["albums"] or "[]")

    def summary(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "count": len(self.albums),
            "created": self.created,
            "updated": self.updated,
            # The version of the shelf's own cover, or null to draw it from its albums.
            "cover": self.cover,
            # What the card's collage is drawn from.
            "preview": self.albums[:PREVIEW_COVERS],
            # So "Add to shelf" can tick the shelves an album is already on.
            "albumIds": [a["id"] for a in self.albums],
        }

    def detail(self) -> dict:
        d = self.summary()
        d.pop("albumIds")
        d["albums"] = self.albums
        return d


class ShelfStore:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.covers_dir = path.parent / "shelf-covers"
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock:
            self._db.execute(
                """create table if not exists shelves (
                    id text primary key, name text, description text,
                    created real, updated real, albums text default '[]')"""
            )
            columns = {r[1] for r in self._db.execute("pragma table_info(shelves)")}
            if "cover" not in columns:
                self._db.execute("alter table shelves add column cover text")
            self._db.commit()

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def all(self) -> list[Shelf]:
        with self._lock:
            rows = self._db.execute("select * from shelves order by updated desc").fetchall()
        return [Shelf(r) for r in rows]

    def get(self, shelf_id: str) -> Shelf | None:
        with self._lock:
            row = self._db.execute("select * from shelves where id = ?", (shelf_id,)).fetchone()
        return Shelf(row) if row else None

    def create(self, body: dict) -> Shelf:
        name = _text(body.get("name"), "Name", MAX_NAME, required=True)
        description = _text(body.get("description"), "Description", MAX_DESCRIPTION)
        albums = _unique([parse_album(a) for a in _list(body.get("albums"))])
        now = time.time()
        for album in albums:
            album["added"] = now
        shelf_id = uuid.uuid4().hex[:12]
        with self._lock:
            count = self._db.execute("select count(*) from shelves").fetchone()[0]
            if count >= MAX_SHELVES:
                raise BadShelf(f"You already have {MAX_SHELVES} shelves")
            self._db.execute(
                "insert into shelves (id, name, description, created, updated, albums) values (?, ?, ?, ?, ?, ?)",
                (shelf_id, name, description, now, now, json.dumps(albums)),
            )
            self._db.commit()
        return self.get(shelf_id)

    def edit(self, shelf_id: str, body: dict) -> Shelf | None:
        """Name, description, or the whole album order. Only what the body names changes."""
        shelf = self.get(shelf_id)
        if shelf is None:
            return None
        name = shelf.name
        description = shelf.description
        albums = shelf.albums
        if "name" in body:
            name = _text(body.get("name"), "Name", MAX_NAME, required=True)
        if "description" in body:
            description = _text(body.get("description"), "Description", MAX_DESCRIPTION)
        if "order" in body:
            # A new order of the albums already there, by id. Anything missing
            # from it keeps its place at the end; unknown ids are ignored.
            order = [i for i in _list(body.get("order")) if isinstance(i, str)]
            by_id = {a["id"]: a for a in albums}
            ordered = [by_id[i] for i in dict.fromkeys(order) if i in by_id]
            seen = {a["id"] for a in ordered}
            albums = ordered + [a for a in albums if a["id"] not in seen]
        return self._save(shelf_id, name, description, albums)

    def add_album(self, shelf_id: str, album: dict) -> Shelf | None:
        """At the end, stamped with when, unless it's already there (then only its details change)."""
        shelf = self.get(shelf_id)
        if shelf is None:
            return None
        album = parse_album(album)
        albums = shelf.albums
        for i, a in enumerate(albums):
            if a["id"] == album["id"]:
                # New details, but it was added when it was first added.
                if "added" in a:
                    album["added"] = a["added"]
                albums[i] = album
                break
        else:
            if len(albums) >= MAX_ALBUMS:
                raise BadShelf(f"A shelf holds at most {MAX_ALBUMS} albums")
            album["added"] = time.time()
            albums.append(album)
        return self._save(shelf_id, shelf.name, shelf.description, albums)

    def remove_album(self, shelf_id: str, album_id: str) -> Shelf | None:
        shelf = self.get(shelf_id)
        if shelf is None:
            return None
        albums = [a for a in shelf.albums if a["id"] != album_id]
        return self._save(shelf_id, shelf.name, shelf.description, albums)

    def delete(self, shelf_id: str) -> bool:
        with self._lock:
            cur = self._db.execute("delete from shelves where id = ?", (shelf_id,))
            self._db.commit()
        self._cover_path(shelf_id).unlink(missing_ok=True)
        return cur.rowcount > 0

    # ---- its own cover ---------------------------------------------------------

    def _cover_path(self, shelf_id: str) -> Path:
        return self.covers_dir / shelf_id

    def set_cover(self, shelf_id: str, data: bytes) -> Shelf | None:
        """Replaces the shelf's cover with an uploaded JPEG, PNG or WebP. Raises BadShelf."""
        if self.get(shelf_id) is None:
            return None
        if not data:
            raise BadShelf("No image was sent")
        if len(data) > MAX_COVER:
            raise BadShelf("That image is too large")
        if image_type(data) is None:
            raise BadShelf("A cover must be a JPEG, PNG or WebP image")
        self.covers_dir.mkdir(parents=True, exist_ok=True)
        tmp = self.covers_dir / f".{shelf_id}.tmp"
        tmp.write_bytes(data)
        tmp.replace(self._cover_path(shelf_id))
        version = hashlib.sha256(data).hexdigest()[:12]
        with self._lock:
            self._db.execute(
                "update shelves set cover = ?, updated = ? where id = ?", (version, time.time(), shelf_id)
            )
            self._db.commit()
        return self.get(shelf_id)

    def clear_cover(self, shelf_id: str) -> Shelf | None:
        if self.get(shelf_id) is None:
            return None
        self._cover_path(shelf_id).unlink(missing_ok=True)
        with self._lock:
            self._db.execute("update shelves set cover = null, updated = ? where id = ?", (time.time(), shelf_id))
            self._db.commit()
        return self.get(shelf_id)

    def cover(self, shelf_id: str) -> tuple[bytes, str, str] | None:
        """The cover's bytes, type and version, or None without one."""
        shelf = self.get(shelf_id)
        path = self._cover_path(shelf_id)
        if shelf is None or not shelf.cover or not path.is_file():
            return None
        data = path.read_bytes()
        return data, image_type(data) or "application/octet-stream", shelf.cover

    def _save(self, shelf_id: str, name: str, description: str, albums: list[dict]) -> Shelf:
        with self._lock:
            self._db.execute(
                "update shelves set name = ?, description = ?, albums = ?, updated = ? where id = ?",
                (name, description, json.dumps(albums), time.time(), shelf_id),
            )
            self._db.commit()
        return self.get(shelf_id)


def _list(value) -> list:
    if value is None:
        return []
    if not isinstance(value, list):
        raise BadShelf("Expected a list")
    if len(value) > MAX_ALBUMS:
        raise BadShelf(f"A shelf holds at most {MAX_ALBUMS} albums")
    return value


def _unique(albums: list[dict]) -> list[dict]:
    seen: set[str] = set()
    out = []
    for a in albums:
        if a["id"] not in seen:
            seen.add(a["id"])
            out.append(a)
    return out
