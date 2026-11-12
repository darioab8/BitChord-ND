"""Download jobs, kept in SQLite so a restart doesn't lose the queue."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path

from . import albums

QUEUED = "queued"
DOWNLOADING = "downloading"
TAGGING = "tagging"
SCANNING = "scanning"
DONE = "done"
SKIPPED = "skipped"  # beets already had the album
FAILED = "failed"
CANCELLED = "cancelled"

FINISHED = {DONE, SKIPPED, FAILED, CANCELLED}
RUNNING = {DOWNLOADING, TAGGING, SCANNING}


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

    def public(self) -> dict:
        d = asdict(self)
        d.pop("request")
        d["finished"] = self.state in FINISHED
        d["source"] = "url" if self.request.get("url") else "tracks"
        return d


class JobStore:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._lock = threading.Lock()
        self._wake = threading.Condition(self._lock)
        with self._lock:
            self._db.execute(
                """create table if not exists jobs (
                    id text primary key, state text, title text, artist text, folder text,
                    progress integer, total integer, message text,
                    created real, updated real, request text)"""
            )
            # A job that was running when the process stopped never finished:
            # put it back in the queue rather than leave it "downloading" forever.
            self._db.execute(
                "update jobs set state = ?, message = ? where state in (?, ?, ?)",
                (QUEUED, "Restarted after the companion stopped", DOWNLOADING, TAGGING, SCANNING),
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
        )
        with self._wake:
            self._db.execute(
                "insert into jobs values (?,?,?,?,?,?,?,?,?,?,?)",
                (job.id, job.state, job.title, job.artist, job.folder, job.progress, job.total,
                 job.message, job.created, job.updated, json.dumps(job.request)),
            )
            self._db.commit()
            self._wake.notify_all()
        return job

    def update(self, job_id: str, **fields) -> None:
        if not fields:
            return
        fields["updated"] = time.time()
        cols = ", ".join(f"{k} = ?" for k in fields)
        with self._lock:
            self._db.execute(f"update jobs set {cols} where id = ?", (*fields.values(), job_id))
            self._db.commit()

    def remove(self, job_id: str) -> bool:
        with self._lock:
            cur = self._db.execute(
                "delete from jobs where id = ? and state in (?, ?, ?, ?)", (job_id, *sorted(FINISHED))
            )
            self._db.commit()
            return cur.rowcount > 0

    def next_queued(self, timeout: float) -> Job | None:
        """The oldest queued job, waiting up to [timeout] seconds for one."""
        with self._wake:
            row = self._db.execute(
                "select * from jobs where state = ? order by created limit 1", (QUEUED,)
            ).fetchone()
            if row is None:
                self._wake.wait(timeout)
                row = self._db.execute(
                    "select * from jobs where state = ? order by created limit 1", (QUEUED,)
                ).fetchone()
        return _job(row) if row else None


def _job(row) -> Job:
    return Job(
        id=row[0], state=row[1], title=row[2], artist=row[3], folder=row[4],
        progress=row[5], total=row[6], message=row[7], created=row[8], updated=row[9],
        request=json.loads(row[10]),
    )
