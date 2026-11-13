"""Download jobs, kept in SQLite so a restart doesn't lose the queue."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from . import albums

QUEUED = "queued"
DOWNLOADING = "downloading"
TAGGING = "tagging"
IMPORTING = "importing"  # host mode: waiting for the host's beets
SCANNING = "scanning"
DONE = "done"
SKIPPED = "skipped"  # beets didn't import it (already in the library)
FAILED = "failed"
CANCELLED = "cancelled"

FINISHED = {DONE, SKIPPED, FAILED, CANCELLED}
RUNNING = {DOWNLOADING, TAGGING, IMPORTING, SCANNING}

# One track of a job.
TRACK_WAITING = "waiting"
TRACK_DOWNLOADING = "downloading"
TRACK_DONE = "done"
TRACK_FAILED = "failed"
TRACK_SKIPPED = "skipped"  # left out on purpose: "import without the failed tracks"


@dataclass
class Job:
    id: str
    state: str
    title: str
    artist: str
    folder: str
    progress: int
    total: int
    message: str
    created: float
    updated: float
    request: dict
    tracks: list[dict] = field(default_factory=list)

    @property
    def failed_tracks(self) -> int:
        return sum(1 for t in self.tracks if t.get("state") == TRACK_FAILED)

    @property
    def retryable(self) -> bool:
        """A failed or cancelled job that can be run again, keeping what it already has."""
        return self.state in (FAILED, CANCELLED)

    def public(self, detail: bool = False) -> dict:
        d = {
            "id": self.id,
            "state": self.state,
            "title": self.title,
            "artist": self.artist,
            "folder": self.folder,
            "progress": self.progress,
            "total": self.total,
            "message": self.message,
            "created": self.created,
            "updated": self.updated,
            "finished": self.state in FINISHED,
            "source": "url" if self.request.get("url") else "tracks",
            "failedTracks": self.failed_tracks,
            "retryable": self.retryable,
        }
        if detail:
            d["tracks"] = [
                {k: t.get(k) for k in ("number", "title", "artist", "state", "error")} for t in self.tracks
            ]
        return d


COLUMNS = ("id", "state", "title", "artist", "folder", "progress", "total", "message",
           "created", "updated", "request", "tracks")


class JobStore:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        self._wake = threading.Condition(self._lock)
        with self._lock:
            self._db.execute(
                """create table if not exists jobs (
                    id text primary key, state text, title text, artist text, folder text,
                    progress integer, total integer, message text,
                    created real, updated real, request text, tracks text default '[]')"""
            )
            # Databases from before per-track state: add the column.
            have = {r["name"] for r in self._db.execute("pragma table_info(jobs)")}
            if "tracks" not in have:
                self._db.execute("alter table jobs add column tracks text default '[]'")
            # A job that was running when the process stopped never finished:
            # put it back in the queue; it picks up the tracks it already has.
            self._db.execute(
                "update jobs set state = ?, message = ? where state in (?, ?, ?)",
                (QUEUED, "Restarted after the companion stopped", DOWNLOADING, TAGGING, SCANNING),
            )
            # Already handed to the host's beets: downloading it again would
            # leave a second copy in the inbox. Say where it is instead.
            self._db.execute(
                "update jobs set state = ?, message = ? where state = ?",
                (FAILED, "The companion restarted while beets was importing. "
                         "Check incoming_music: the album may still be there.", IMPORTING),
            )
            self._db.commit()

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ---- reads ---------------------------------------------------------------

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            row = self._db.execute("select * from jobs where id = ?", (job_id,)).fetchone()
        return _job(row) if row else None

    def recent(self, limit: int = 50) -> list[Job]:
        with self._lock:
            rows = self._db.execute("select * from jobs order by created desc limit ?", (limit,)).fetchall()
        return [_job(r) for r in rows]

    def find_active(self, browse_id: str | None, url: str | None) -> Job | None:
        """The unfinished job for the same album, so a double tap doesn't queue it twice."""
        for job in self.recent(200):
            if job.state in FINISHED:
                continue
            if browse_id and job.request.get("browseId") == browse_id:
                return job
            if url and job.request.get("url") == url:
                return job
        return None

    # ---- writes --------------------------------------------------------------

    def add(self, request: albums.AlbumRequest) -> Job:
        now = time.time()
        job = Job(
            id=uuid.uuid4().hex[:12],
            state=QUEUED,
            title=request.title or (request.url or "Album"),
            artist=request.artist,
            folder=request.folder,
            progress=0,
            total=len(request.tracks),
            message="Waiting",
            created=now,
            updated=now,
            request=request.to_json(),
            tracks=[
                {"number": n, "videoId": t.video_id, "title": t.title, "artist": t.artist,
                 "state": TRACK_WAITING, "error": ""}
                for n, t in enumerate(request.tracks, 1)
            ],
        )
        with self._wake:
            self._db.execute(
                f"insert into jobs ({', '.join(COLUMNS)}) values ({', '.join('?' * len(COLUMNS))})",
                (job.id, job.state, job.title, job.artist, job.folder, job.progress, job.total,
                 job.message, job.created, job.updated, json.dumps(job.request), json.dumps(job.tracks)),
            )
            self._db.commit()
            self._wake.notify_all()
        return job

    def update(self, job_id: str, **fields) -> None:
        if not fields:
            return
        fields["updated"] = time.time()
        if "tracks" in fields:
            fields["tracks"] = json.dumps(fields["tracks"])
        cols = ", ".join(f"{k} = ?" for k in fields)
        with self._lock:
            self._db.execute(f"update jobs set {cols} where id = ?", (*fields.values(), job_id))
            self._db.commit()

    def update_track(self, job_id: str, number: int, state: str, error: str = "") -> None:
        with self._lock:
            row = self._db.execute("select tracks from jobs where id = ?", (job_id,)).fetchone()
            if row is None:
                return
            tracks = json.loads(row["tracks"] or "[]")
            for t in tracks:
                if t.get("number") == number:
                    t["state"] = state
                    t["error"] = error
            self._db.execute(
                "update jobs set tracks = ?, updated = ? where id = ?", (json.dumps(tracks), time.time(), job_id)
            )
            self._db.commit()

    def retry(self, job_id: str, skip_failed: bool) -> Job | None:
        """Queues a failed or cancelled job again.

        Tracks already downloaded are kept. With [skip_failed] the failed ones
        are left out and the album goes on to tagging and import without them;
        otherwise they're tried again.
        """
        with self._wake:
            row = self._db.execute("select * from jobs where id = ?", (job_id,)).fetchone()
            if row is None:
                return None
            job = _job(row)
            if not job.retryable:
                return None
            for t in job.tracks:
                if t.get("state") == TRACK_FAILED:
                    t["state"] = TRACK_SKIPPED if skip_failed else TRACK_WAITING
                    if not skip_failed:
                        t["error"] = ""
                elif t.get("state") == TRACK_DOWNLOADING:
                    t["state"] = TRACK_WAITING
            message = "Waiting (without the failed tracks)" if skip_failed else "Waiting to retry"
            self._db.execute(
                "update jobs set state = ?, message = ?, tracks = ?, updated = ? where id = ?",
                (QUEUED, message, json.dumps(job.tracks), time.time(), job_id),
            )
            self._db.commit()
            self._wake.notify_all()
        return self.get(job_id)

    def remove(self, job_id: str) -> bool:
        with self._lock:
            cur = self._db.execute(
                "delete from jobs where id = ? and state in (?, ?, ?, ?)", (job_id, *sorted(FINISHED))
            )
            self._db.commit()
            return cur.rowcount > 0

    def next_queued(self, timeout: float) -> Job | None:
        """The queued job waiting longest (a retry joins the back of the queue)."""
        query = "select * from jobs where state = ? order by updated limit 1"
        with self._wake:
            row = self._db.execute(query, (QUEUED,)).fetchone()
            if row is None:
                self._wake.wait(timeout)
                row = self._db.execute(query, (QUEUED,)).fetchone()
        return _job(row) if row else None


def _job(row: sqlite3.Row) -> Job:
    return Job(
        id=row["id"], state=row["state"], title=row["title"], artist=row["artist"], folder=row["folder"],
        progress=row["progress"], total=row["total"], message=row["message"],
        created=row["created"], updated=row["updated"],
        request=json.loads(row["request"]),
        tracks=json.loads(row["tracks"] or "[]"),
    )
