"""The HTTP side: a small JSON API for the app, and one page for a browser.

    GET    /                    the web page (asks for the token itself)
    GET    /api/health          versions and settings the app shows
    GET    /api/jobs            recent jobs, newest first
    POST   /api/jobs            queue an album (see albums.parse)
    GET    /api/jobs/<id>       one job
    POST   /api/jobs/<id>/cancel
    POST   /api/jobs/<id>/retry {"skipFailed": bool}  the failed tracks again, or import without them
    DELETE /api/jobs/<id>       forget a finished job

    GET    /api/shelves                     every shelf, newest change first
    POST   /api/shelves                     {name, description?, albums?}
    GET    /api/shelves/<id>                one shelf with its albums
    PUT    /api/shelves/<id>                {name?, description?, order?}
    DELETE /api/shelves/<id>
    POST   /api/shelves/<id>/albums         {album} add (or refresh) one album
    POST   /api/shelves/<id>/albums/remove  {id} take one off
    PUT    /api/shelves/<id>/cover          the image itself as the body (JPEG, PNG, WebP)
    DELETE /api/shelves/<id>/cover          back to the albums' covers

    GET    /covers/<id>?v=<version>&sig=<signature>   a shelf's cover, no token
           (the signature is HMAC-SHA256(token, "<id>:<version>"), first 32 hex)

Every /api call needs `Authorization: Bearer <COMPANION_TOKEN>`.
"""
from __future__ import annotations

import hmac
import json
import logging
import re
import shutil
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources

from . import albums, jobs, shelves
from .config import Config
from .worker import Worker

log = logging.getLogger("companion")
VERSION = "0.4.0"
MAX_BODY = 512 * 1024
JOB_PATH = re.compile(r"^/api/jobs/([0-9a-f]{12})(/cancel|/retry)?$")
SHELF_PATH = re.compile(r"^/api/shelves/([0-9a-f]{12})(/albums|/albums/remove|/cover)?$")
COVER_PATH = re.compile(r"^/covers/([0-9a-f]{12})$")


class App:
    """What the handler needs, bundled so tests can build one without a socket."""

    def __init__(self, cfg: Config, store: jobs.JobStore, worker: Worker, versions: dict,
                 shelf_store: shelves.ShelfStore | None = None):
        self.cfg = cfg
        self.store = store
        self.worker = worker
        self.versions = versions
        self.shelves = shelf_store or shelves.ShelfStore(cfg.config_dir / "shelves.db")

    def health(self) -> dict:
        return {
            "ok": True,
            "version": VERSION,
            "ytDlp": self.versions.get("yt-dlp"),
            "beets": self.versions.get("beets"),
            "premiumCookies": self.cfg.cookies is not None,
            "importMode": self.cfg.import_mode,
            # Host mode: beets on the server decides where albums go.
            "defaultFolder": "" if self.cfg.host_import else self.cfg.default_folder,
            "navidromeRescan": self.cfg.navidrome_configured,
            "shelves": True,
            "shelfCovers": True,
        }

    def handle(self, method: str, path: str, body: bytes | None) -> tuple[int, dict]:
        if path == "/api/shelves" or path.startswith("/api/shelves/"):
            try:
                return self.handle_shelves(method, path, body)
            except json.JSONDecodeError:
                return 400, {"error": "Body is not JSON"}
            except shelves.BadShelf as e:
                return 400, {"error": str(e)}
        if path == "/api/health" and method == "GET":
            return 200, self.health()
        if path == "/api/jobs" and method == "GET":
            return 200, {"jobs": [j.public() for j in self.store.recent()]}
        if path == "/api/jobs" and method == "POST":
            try:
                data = json.loads(body or b"{}")
                request = albums.parse(data, self.cfg.default_folder)
            except json.JSONDecodeError:
                return 400, {"error": "Body is not JSON"}
            except albums.BadRequest as e:
                return 400, {"error": str(e)}
            existing = self.store.find_active(request.browse_id, request.url)
            if existing:
                return 200, existing.public()
            return 202, self.store.add(request).public()
        m = JOB_PATH.match(path)
        if m:
            job_id, action = m.group(1), m.group(2)
            if action == "/cancel" and method == "POST":
                if not self.worker.cancel(job_id):
                    return 409, {"error": "That job isn't queued or running"}
                return 200, self.store.get(job_id).public(detail=True)
            if action == "/retry" and method == "POST":
                try:
                    data = json.loads(body or b"{}")
                except json.JSONDecodeError:
                    return 400, {"error": "Body is not JSON"}
                job = self.store.retry(job_id, skip_failed=bool(data.get("skipFailed")))
                if job is None:
                    return 409, {"error": "Only failed or cancelled downloads can be retried"}
                return 200, job.public(detail=True)
            if action is None and method == "GET":
                job = self.store.get(job_id)
                return (200, job.public(detail=True)) if job else (404, {"error": "No such job"})
            if action is None and method == "DELETE":
                if not self.store.remove(job_id):
                    return 409, {"error": "Only finished jobs can be removed"}
                # Tracks kept for a retry go with the job.
                shutil.rmtree(self.cfg.work_dir / job_id, ignore_errors=True)
                return 200, {"ok": True}
        return 404, {"error": "Not found"}

    def handle_shelves(self, method: str, path: str, body: bytes | None) -> tuple[int, dict]:
        def data() -> dict:
            d = json.loads(body or b"{}")
            if not isinstance(d, dict):
                raise shelves.BadShelf("Body must be an object")
            return d

        if path == "/api/shelves":
            if method == "GET":
                return 200, {"shelves": [s.summary() for s in self.shelves.all()]}
            if method == "POST":
                return 201, self.shelves.create(data()).detail()
            return 405, {"error": "Method not allowed"}
        m = SHELF_PATH.match(path)
        if not m:
            return 404, {"error": "Not found"}
        shelf_id, action = m.group(1), m.group(2)
        shelf = None
        if action is None:
            if method == "GET":
                shelf = self.shelves.get(shelf_id)
            elif method == "PUT":
                shelf = self.shelves.edit(shelf_id, data())
            elif method == "DELETE":
                if not self.shelves.delete(shelf_id):
                    return 404, {"error": "No such shelf"}
                return 200, {"ok": True}
            else:
                return 405, {"error": "Method not allowed"}
        elif action == "/albums" and method == "POST":
            shelf = self.shelves.add_album(shelf_id, data().get("album"))
        elif action == "/cover" and method == "PUT":
            shelf = self.shelves.set_cover(shelf_id, body or b"")
        elif action == "/cover" and method == "DELETE":
            shelf = self.shelves.clear_cover(shelf_id)
        elif action == "/albums/remove" and method == "POST":
            album_id = data().get("id")
            if not isinstance(album_id, str) or not album_id:
                raise shelves.BadShelf("Album id is required")
            shelf = self.shelves.remove_album(shelf_id, album_id)
        else:
            return 405, {"error": "Method not allowed"}
        if shelf is None:
            return 404, {"error": "No such shelf"}
        return 200, shelf.detail()

    def shelf_cover(self, path: str, query: dict[str, str]) -> tuple[bytes, str] | None:
        """A signed cover link's image, or None for anything else."""
        m = COVER_PATH.match(path)
        if not m:
            return None
        found = self.shelves.cover(m.group(1))
        if found is None:
            return None
        data, mime, version = found
        expected = shelves.cover_signature(self.cfg.token, m.group(1), version)
        if query.get("v") != version or not hmac.compare_digest(query.get("sig", ""), expected):
            return None
        return data, mime

    def authorized(self, header: str | None) -> bool:
        if not header or not header.startswith("Bearer "):
            return False
        return hmac.compare_digest(header[7:].strip().encode(), self.cfg.token.encode())


def make_handler(app: App):
    page = resources.files("bitchord_companion").joinpath("web/index.html").read_bytes()

    class Handler(BaseHTTPRequestHandler):
        server_version = f"bitchord-nd-companion/{VERSION}"

        def log_message(self, fmt, *args):  # quieter than the default stderr spam
            log.debug("%s %s", self.address_string(), fmt % args)

        def _send(self, code: int, payload: dict | bytes, content_type: str = "application/json",
                  cache: str = "no-store") -> None:
            data = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", cache)
            self.end_headers()
            self.wfile.write(data)

        def _dispatch(self, method: str) -> None:
            path, _, query = self.path.partition("?")
            if method == "GET" and path in ("/", "/index.html"):
                return self._send(200, page, "text/html; charset=utf-8")
            if method == "GET" and path.startswith("/covers/"):
                params = {k: v[-1] for k, v in urllib.parse.parse_qs(query).items()}
                found = app.shelf_cover(path, params)
                if found is None:
                    return self._send(404, {"error": "Not found"})
                # The link changes with the image, so it can be kept for good.
                return self._send(200, found[0], found[1], cache="public, max-age=31536000, immutable")
            if not path.startswith("/api/"):
                return self._send(404, {"error": "Not found"})
            if not app.authorized(self.headers.get("Authorization")):
                return self._send(401, {"error": "Wrong or missing token"})
            body = None
            if method in ("POST", "PUT"):
                length = int(self.headers.get("Content-Length") or 0)
                limit = shelves.MAX_COVER if path.endswith("/cover") else MAX_BODY
                if length > limit:
                    return self._send(413, {"error": "Request too large"})
                body = self.rfile.read(length) if length else b""
            code, payload = app.handle(method, path, body)
            self._send(code, payload)

        def do_GET(self):
            self._dispatch("GET")

        def do_POST(self):
            self._dispatch("POST")

        def do_PUT(self):
            self._dispatch("PUT")

        def do_DELETE(self):
            self._dispatch("DELETE")

    return Handler


def serve(app: App, port: int) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer(("0.0.0.0", port), make_handler(app))
    server.daemon_threads = True
    return server
