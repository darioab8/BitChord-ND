"""One album at a time, in the order they were asked for."""
from __future__ import annotations

import logging
import threading
from pathlib import Path

from . import jobs, pipeline
from .config import Config

log = logging.getLogger("companion")


class Worker:
    def __init__(self, cfg: Config, beets_config: Path, store: jobs.JobStore, tools: pipeline.Tools):
        self.cfg = cfg
        self.beets_config = beets_config
        self.store = store
        self.tools = tools
        self._stop = threading.Event()
        self._current: str | None = None
        self._cancel = threading.Event()
        self._lock = threading.Lock()
        self._thread = threading.Thread(target=self._loop, name="worker", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._cancel.set()

    def cancel(self, job_id: str) -> bool:
        """Cancels a queued job outright, or stops the running one."""
        with self._lock:
            if self._current == job_id:
                self._cancel.set()
                return True
        job = self.store.get(job_id)
        if job and job.state == jobs.QUEUED:
            self.store.update(job_id, state=jobs.CANCELLED, message="Cancelled")
            return True
        return False

    def run_one(self, timeout: float = 5.0) -> bool:
        """Runs the next queued job, if one turns up within [timeout]. For the loop and the tests."""
        job = self.store.next_queued(timeout)
        if job is None:
            return False
        with self._lock:
            self._current = job.id
            self._cancel = threading.Event()
            cancel = self._cancel
        log.info("starting %s: %s – %s", job.id, job.artist, job.title)
        try:
            result = pipeline.run(job, self.cfg, self.beets_config, self.store, self.tools, cancel)
        except Exception as e:  # a bug, not a failed download: still finish the job
            log.exception("job %s crashed", job.id)
            result = pipeline.Result(jobs.FAILED, f"Internal error: {e}")
        finally:
            with self._lock:
                self._current = None
        self.store.update(job.id, state=result.state, message=result.message)
        log.info("finished %s: %s (%s)", job.id, result.state, result.message.splitlines()[0])
        return True

    def _loop(self) -> None:
        while not self._stop.is_set():
            self.run_one()
