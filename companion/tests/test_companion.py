import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from bitchord_companion import albums, jobs, pipeline, services
from bitchord_companion.config import from_env
from bitchord_companion.server import App, serve
from bitchord_companion.worker import Worker

TOKEN = "t" * 32


def track(i):
    return {"videoId": f"abcdefghi{i:02d}", "title": f"Song {i}", "artist": "Band"}


class FakeRunner(pipeline.Runner):
    """Pretends to be yt-dlp and beets: writes files where they would."""

    def __init__(self, fail_tracks=(), beets_skips=False, beets_code=0, block=None):
        self.calls = []
        self.fail_tracks = set(fail_tracks)
        self.beets_skips = beets_skips
        self.beets_code = beets_code
        self.block = block

    def run(self, cmd, on_line, cancel):
        self.calls.append(cmd)
        if self.block is not None:
            self.block.wait(5)
            if cancel.is_set():
                raise pipeline.Cancelled()
        if cmd[0] == "yt-dlp":
            out = Path(cmd[cmd.index("--output") + 1])
            if "--yes-playlist" in cmd:
                for n in (1, 2, 3):
                    on_line(f"[download] Downloading item {n} of 3")
                    Path(str(out).replace("%(playlist_index)02d.%(ext)s", f"{n:02d}.opus")).write_bytes(b"x")
                return 0, ""
            number = int(out.name.split(".")[0])
            if number in self.fail_tracks:
                return 1, "ERROR: [youtube] Video unavailable"
            Path(str(out).replace("%(ext)s", "opus")).write_bytes(b"x")
            return 0, ""
        if cmd[0] == "beet":
            staging = Path(cmd[-1])
            library = Path(cmd[cmd.index("-d") + 1])
            if not self.beets_skips and self.beets_code == 0:
                for f in staging.glob("*.opus"):
                    f.rename(library / f.name)
            return self.beets_code, "beets output"
        raise AssertionError(cmd)


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        (root / "music").mkdir()
        self.cfg = from_env({
            "COMPANION_TOKEN": TOKEN,
            "MUSIC_DIR": str(root / "music"),
            "CONFIG_DIR": str(root / "config"),
            "DEFAULT_FOLDER": "YouTube",
        })
        self.store = jobs.JobStore(self.cfg.config_dir / "jobs.db")
        self.tags = {}
        self.rescans = 0

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def tools(self, runner):
        def rescan():
            self.rescans += 1
            return None
        return pipeline.Tools(
            runner=runner,
            tag=lambda path, tags: self.tags.__setitem__(path.name, tags),
            fetch=lambda url: b"jpeg",
            rescan=rescan,
        )

    def queue(self, **body):
        body.setdefault("title", "Album")
        body.setdefault("artist", "Band")
        return self.store.add(albums.parse(body, self.cfg.default_folder))

    def run_job(self, runner):
        worker = Worker(self.cfg, Path("/beets.yaml"), self.store, self.tools(runner))
        self.assertTrue(worker.run_one(timeout=0.1))
        return worker


class RequestTests(unittest.TestCase):
    def test_folder_never_leaves_the_library(self):
        self.assertEqual(albums.safe_folder("../../etc", "YouTube"), "etc")
        self.assertEqual(albums.safe_folder("/YouTube/2026/", "x"), "YouTube/2026")
        self.assertEqual(albums.safe_folder("..\\..\\", "YouTube"), "YouTube")
        self.assertEqual(albums.safe_folder("", ""), "")
        self.assertEqual(albums.safe_folder("a:b", ""), "a_b")

    def test_only_youtube(self):
        with self.assertRaises(albums.BadRequest):
            albums.parse({"url": "https://example.com/album"}, "")
        with self.assertRaises(albums.BadRequest):
            albums.parse({"title": "x", "tracks": [{"videoId": "../../etc"}]}, "")
        with self.assertRaises(albums.BadRequest):
            albums.parse({"title": "x"}, "")
        ok = albums.parse({"url": "https://music.youtube.com/playlist?list=OLAK5uy_x"}, "YouTube")
        self.assertEqual(ok.folder, "YouTube")

    def test_cover_from_google_hosts_only(self):
        r = albums.parse({"title": "x", "tracks": [track(1)], "coverUrl": "http://169.254.169.254/latest"}, "")
        self.assertIsNone(r.cover_url)
        r = albums.parse({"title": "x", "tracks": [track(1)], "coverUrl": "https://lh3.googleusercontent.com/a=w1200"}, "")
        self.assertIsNotNone(r.cover_url)


class PipelineTests(Base):
    def test_tracks_are_downloaded_tagged_imported_and_scanned(self):
        self.cfg = self.cfg.__class__(**{**self.cfg.__dict__, "navidrome_url": "http://nd", "navidrome_user": "admin"})
        job = self.queue(tracks=[track(1), track(2)], year=1999, browseId="MPREb_x",
                         coverUrl="https://lh3.googleusercontent.com/c")
        runner = FakeRunner()
        self.run_job(runner)
        done = self.store.get(job.id)
        self.assertEqual(done.state, jobs.DONE, done.message)
        self.assertEqual(self.tags["01.opus"]["track"], 1)
        self.assertEqual(self.tags["02.opus"]["album"], "Album")
        self.assertEqual(self.tags["02.opus"]["tracktotal"], 2)
        self.assertEqual(self.tags["01.opus"]["year"], 1999)
        beet = runner.calls[-1]
        self.assertIn("source=youtube", beet)
        self.assertIn("youtube_album=MPREb_x", beet)
        self.assertEqual(Path(beet[beet.index("-d") + 1]), self.cfg.music_dir / "YouTube")
        self.assertEqual(sorted(p.name for p in (self.cfg.music_dir / "YouTube").iterdir()), ["01.opus", "02.opus"])
        self.assertEqual(self.rescans, 1)
        self.assertFalse((self.cfg.work_dir / job.id).exists())

    def test_no_reencode_and_cookies_only_when_present(self):
        cmd = pipeline.ytdlp_track(self.cfg, "abcdefghijk", 1, Path("/w"))
        self.assertNotIn("--audio-format", cmd)
        self.assertNotIn("--cookies", cmd)
        self.cfg.config_dir.mkdir(parents=True, exist_ok=True)
        self.cfg.cookies_file.write_text("# Netscape HTTP Cookie File\n")
        self.assertIn("--cookies", pipeline.ytdlp_track(self.cfg, "abcdefghijk", 1, Path("/w")))

    def test_a_failed_track_fails_the_album_and_names_it(self):
        job = self.queue(tracks=[track(1), track(2), track(3)])
        self.run_job(FakeRunner(fail_tracks={2}))
        failed = self.store.get(job.id)
        self.assertEqual(failed.state, jobs.FAILED)
        first_line = failed.message.splitlines()[0]
        self.assertIn("2. Song 2 didn't download (1 of 3 failed)", first_line)
        self.assertIn("Video unavailable", failed.message)
        self.assertEqual(failed.failed_tracks, 1)
        self.assertEqual([t["state"] for t in failed.tracks], ["done", "failed", "done"])
        self.assertEqual(list(self.cfg.music_dir.iterdir()), [])
        # The two good tracks are kept for a retry.
        self.assertEqual(sorted(p.name for p in (self.cfg.work_dir / job.id).iterdir()), ["01.opus", "03.opus"])

    def test_retry_downloads_only_the_failed_tracks(self):
        job = self.queue(tracks=[track(1), track(2), track(3)])
        self.run_job(FakeRunner(fail_tracks={2}))
        self.assertIsNotNone(self.store.retry(job.id, skip_failed=False))
        runner = FakeRunner()
        self.run_job(runner)
        done = self.store.get(job.id)
        self.assertEqual(done.state, jobs.DONE, done.message)
        downloads = [c for c in runner.calls if c[0] == "yt-dlp"]
        self.assertEqual(len(downloads), 1)
        self.assertIn("watch?v=abcdefghi02", downloads[0][-1])
        self.assertEqual(
            sorted(p.name for p in (self.cfg.music_dir / "YouTube").iterdir()), ["01.opus", "02.opus", "03.opus"]
        )
        self.assertFalse((self.cfg.work_dir / job.id).exists())

    def test_import_without_the_failed_tracks(self):
        job = self.queue(tracks=[track(1), track(2), track(3)])
        self.run_job(FakeRunner(fail_tracks={2}))
        self.store.retry(job.id, skip_failed=True)
        runner = FakeRunner(fail_tracks={2})
        self.run_job(runner)
        done = self.store.get(job.id)
        self.assertEqual(done.state, jobs.DONE, done.message)
        self.assertEqual([c for c in runner.calls if c[0] == "yt-dlp"], [])
        self.assertEqual([t["state"] for t in done.tracks], ["done", "skipped", "done"])
        self.assertEqual(sorted(p.name for p in (self.cfg.music_dir / "YouTube").iterdir()), ["01.opus", "03.opus"])
        self.assertEqual(self.tags["03.opus"]["tracktotal"], 3)

    def test_only_failed_or_cancelled_jobs_retry(self):
        job = self.queue(tracks=[track(1)])
        self.assertIsNone(self.store.retry(job.id, skip_failed=False))
        self.run_job(FakeRunner())
        self.assertIsNone(self.store.retry(job.id, skip_failed=False))

    def test_old_databases_get_track_state(self):
        import sqlite3
        path = self.cfg.config_dir / "old.db"
        db = sqlite3.connect(str(path))
        db.execute("""create table jobs (id text primary key, state text, title text, artist text, folder text,
                      progress integer, total integer, message text, created real, updated real, request text)""")
        req = albums.parse({"title": "A", "tracks": [track(1)]}, "").to_json()
        db.execute("insert into jobs values ('abcabcabcabc','failed','A','','',0,1,'x',1,1,?)", (json.dumps(req),))
        db.commit()
        db.close()
        store = jobs.JobStore(path)
        old = store.get("abcabcabcabc")
        self.assertEqual(old.tracks, [])
        self.assertTrue(old.retryable)
        store.close()

    def test_url_mode_reports_progress(self):
        job = self.queue(url="https://music.youtube.com/playlist?list=OLAK5uy_abc", title="")
        self.run_job(FakeRunner())
        done = self.store.get(job.id)
        self.assertEqual(done.state, jobs.DONE, done.message)
        self.assertEqual(done.total, 3)

    def test_beets_skipping_a_duplicate_is_reported(self):
        job = self.queue(tracks=[track(1)])
        self.run_job(FakeRunner(beets_skips=True))
        self.assertEqual(self.store.get(job.id).state, jobs.SKIPPED)

    def test_beets_error_fails_with_its_output(self):
        job = self.queue(tracks=[track(1)])
        self.run_job(FakeRunner(beets_code=1))
        failed = self.store.get(job.id)
        self.assertEqual(failed.state, jobs.FAILED)
        self.assertIn("beets output", failed.message)

    def test_cancel_a_running_job(self):
        job = self.queue(tracks=[track(1)])
        gate = threading.Event()
        worker = Worker(self.cfg, Path("/b"), self.store, self.tools(FakeRunner(block=gate)))
        t = threading.Thread(target=worker.run_one, kwargs={"timeout": 1})
        t.start()
        for _ in range(50):
            if self.store.get(job.id).state == jobs.DOWNLOADING:
                break
            threading.Event().wait(0.02)
        self.assertTrue(worker.cancel(job.id))
        gate.set()
        t.join(5)
        self.assertEqual(self.store.get(job.id).state, jobs.CANCELLED)

    def test_restart_requeues_interrupted_jobs(self):
        job = self.queue(tracks=[track(1)])
        self.store.update(job.id, state=jobs.DOWNLOADING)
        again = jobs.JobStore(self.cfg.config_dir / "jobs.db")
        self.assertEqual(again.get(job.id).state, jobs.QUEUED)
        again.close()


class BeetsConfigTests(Base):
    def test_generated_config(self):
        text = services.default_beets_config(self.cfg, ["musicbrainz", "fetchart", "embedart"])
        self.assertIn("quiet_fallback: asis", text)
        self.assertIn("duplicate_action: skip", text)
        self.assertIn("plugins: musicbrainz fetchart embedart", text)
        self.assertIn(str(self.cfg.config_dir / "beets" / "library.db"), text)
        self.assertIn("plugins: []", services.default_beets_config(self.cfg, []))


class ServerTests(Base):
    def setUp(self):
        super().setUp()
        worker = Worker(self.cfg, Path("/b"), self.store, self.tools(FakeRunner()))
        self.server = serve(App(self.cfg, self.store, worker, {"yt-dlp": "2026.11.01", "beets": "beets 2.5"}), 0)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        super().tearDown()

    def call(self, method, path, body=None, token=TOKEN):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_token_required(self):
        self.assertEqual(self.call("GET", "/api/health", token=None)[0], 401)
        self.assertEqual(self.call("GET", "/api/health", token="wrong")[0], 401)
        code, health = self.call("GET", "/api/health")
        self.assertEqual(code, 200)
        self.assertEqual(health["defaultFolder"], "YouTube")
        self.assertFalse(health["premiumCookies"])

    def test_page_is_served_without_token(self):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/", timeout=5) as r:
            self.assertIn(b"BitChord ND companion", r.read())

    def test_queue_list_and_double_tap(self):
        body = {"title": "Album", "artist": "Band", "browseId": "MPREb_1", "tracks": [track(1)]}
        code, job = self.call("POST", "/api/jobs", body)
        self.assertEqual(code, 202)
        self.assertEqual(job["state"], "queued")
        code, again = self.call("POST", "/api/jobs", body)
        self.assertEqual((code, again["id"]), (200, job["id"]))
        code, listing = self.call("GET", "/api/jobs")
        self.assertEqual([j["id"] for j in listing["jobs"]], [job["id"]])
        self.assertEqual(self.call("GET", f"/api/jobs/{job['id']}")[1]["title"], "Album")

    def test_bad_requests(self):
        self.assertEqual(self.call("POST", "/api/jobs", {"url": "https://evil.example/x"})[0], 400)
        self.assertEqual(self.call("GET", "/api/jobs/000000000000")[0], 404)

    def test_detail_retry_and_remove_clears_kept_tracks(self):
        _, job = self.call("POST", "/api/jobs", {"title": "A", "tracks": [track(1), track(2)]})
        code, detail = self.call("GET", f"/api/jobs/{job['id']}")
        self.assertEqual([t["state"] for t in detail["tracks"]], ["waiting", "waiting"])
        self.assertNotIn("tracks", self.call("GET", "/api/jobs")[1]["jobs"][0])
        self.assertEqual(self.call("POST", f"/api/jobs/{job['id']}/retry", {})[0], 409)
        self.store.update(job["id"], state=jobs.FAILED)
        self.store.update_track(job["id"], 2, jobs.TRACK_FAILED, "ERROR: gone")
        kept = self.cfg.work_dir / job["id"]
        kept.mkdir(parents=True)
        (kept / "01.opus").write_bytes(b"x")
        code, retried = self.call("POST", f"/api/jobs/{job['id']}/retry", {"skipFailed": True})
        self.assertEqual((code, retried["state"]), (200, "queued"))
        self.assertEqual(retried["tracks"][1]["state"], "skipped")
        self.store.update(job["id"], state=jobs.FAILED)
        self.assertEqual(self.call("DELETE", f"/api/jobs/{job['id']}")[0], 200)
        self.assertFalse(kept.exists())

    def test_cancel_queued_then_remove(self):
        _, job = self.call("POST", "/api/jobs", {"title": "A", "tracks": [track(1)]})
        code, cancelled = self.call("POST", f"/api/jobs/{job['id']}/cancel")
        self.assertEqual((code, cancelled["state"]), (200, "cancelled"))
        self.assertEqual(self.call("DELETE", f"/api/jobs/{job['id']}")[0], 200)
        self.assertEqual(self.call("GET", f"/api/jobs/{job['id']}")[0], 404)


if __name__ == "__main__":
    unittest.main()


HOST_SCRIPT = Path(__file__).resolve().parent.parent / "host" / "bitchord-import.sh"
FAKE_BEET = Path(__file__).resolve().parent / "fakes" / "beet"


class HostModeTests(Base):
    """The companion and the real host importer script, with a fake beet."""

    def setUp(self):
        super().setUp()
        root = Path(self.tmp.name)
        (root / "incoming").mkdir()
        (root / "library").mkdir()
        self.library = root / "library"
        self.cfg = self.cfg.__class__(**{
            **self.cfg.__dict__,
            "import_mode": "host",
            "inbox_dir": root / "incoming",
            "host_pickup_timeout": 2.0,
        })
        self.env = {
            **__import__("os").environ,
            "QUEUE": str(self.cfg.queue_dir),
            "INCOMING": str(self.cfg.inbox_dir),
            "OVERLAY": str(HOST_SCRIPT.parent / "beets-auto.yaml"),
            "BEET": str(FAKE_BEET),
            "FAKE_LIBRARY": str(self.library),
        }
        self.host_stop = threading.Event()

    def tearDown(self):
        self.host_stop.set()
        super().tearDown()

    def tools(self, runner):
        tools = super().tools(runner)

        def tag(path, tags):
            # The fake beet reads comments from a sidecar file, like beets reads the real tag.
            if "comments" in tags:
                path.with_name(path.name + ".comments").write_text(tags["comments"])
            self.tags[path.name] = tags

        tools.tag = tag
        return tools

    def start_host(self, **fake_env):
        """Plays systemd's path unit: runs the script whenever a request shows up."""
        import subprocess

        env = {**self.env, **fake_env}

        def loop():
            while not self.host_stop.wait(0.1):
                if self.cfg.queue_dir.exists() and list(self.cfg.queue_dir.glob("*.request")):
                    subprocess.run(["sh", str(HOST_SCRIPT)], env=env, check=True)

        threading.Thread(target=loop, daemon=True).start()

    def test_imported_and_matched(self):
        self.start_host(FAKE_MBID="0b5c-mbid")
        job = self.queue(tracks=[track(1), track(2)], year=1971, title="Blue", artist="Joni Mitchell")
        self.run_job(FakeRunner())
        done = self.store.get(job.id)
        self.assertEqual(done.state, jobs.DONE, done.message)
        self.assertEqual(done.message, "Imported by beets")
        self.assertEqual(sorted(p.name for p in self.library.glob("*.opus")), ["01.opus", "02.opus"])
        self.assertIn(f"bitchord:{job.id}", self.tags["01.opus"]["comments"])
        # The album folder is gone from the inbox, and the queue is clean.
        self.assertEqual(list(self.cfg.inbox_dir.iterdir()), [])
        self.assertEqual(list(self.cfg.queue_dir.iterdir()), [])
        # Host mode never touches the music folder itself.
        self.assertEqual(list(self.cfg.music_dir.iterdir()), [])

    def test_imported_as_is(self):
        self.start_host(FAKE_MBID="")
        job = self.queue(tracks=[track(1)])
        self.run_job(FakeRunner())
        self.assertEqual(self.store.get(job.id).message, "Imported as-is (no confident MusicBrainz match)")

    def test_skipped_album_stays_in_the_inbox(self):
        self.start_host(FAKE_SKIP="1")
        job = self.queue(tracks=[track(1)], title="Blue", artist="Joni Mitchell", year=1971)
        self.run_job(FakeRunner())
        skipped = self.store.get(job.id)
        self.assertEqual(skipped.state, jobs.SKIPPED)
        self.assertIn("incoming_music/Joni Mitchell - Blue (1971)", skipped.message)
        self.assertTrue((self.cfg.inbox_dir / "Joni Mitchell - Blue (1971)" / "01.opus").exists())

    def test_beets_error_is_reported_with_its_output(self):
        self.start_host(FAKE_FAIL="1")
        job = self.queue(tracks=[track(1)])
        self.run_job(FakeRunner())
        failed = self.store.get(job.id)
        self.assertEqual(failed.state, jobs.FAILED)
        self.assertIn("something broke", failed.message)

    def test_no_host_importer_fails_with_a_hint(self):
        job = self.queue(tracks=[track(1)], title="Blue", artist="Joni Mitchell")
        self.run_job(FakeRunner())
        failed = self.store.get(job.id)
        self.assertEqual(failed.state, jobs.FAILED)
        self.assertIn("bitchord-import.path", failed.message)
        self.assertEqual(list(self.cfg.queue_dir.glob("*.request")), [])
        self.assertTrue((self.cfg.inbox_dir / "Joni Mitchell - Blue").is_dir())

    def test_folder_names(self):
        r = albums.parse({"title": "A/B: C?", "artist": "AC/DC", "tracks": [track(1)], "year": 1980}, "")
        self.assertEqual(pipeline.album_folder_name(r, "x"), "AC_DC - A_B_ C_ (1980)")

    def test_script_refuses_paths_outside_the_inbox(self):
        import subprocess
        self.cfg.queue_dir.mkdir(parents=True)
        (self.cfg.queue_dir / "abc123abc123.request").write_text("../music\n")
        subprocess.run(["sh", str(HOST_SCRIPT)], env=self.env, check=True)
        result = pipeline.parse_result((self.cfg.queue_dir / "abc123abc123.result").read_text())
        self.assertEqual(result["code"], "2")
