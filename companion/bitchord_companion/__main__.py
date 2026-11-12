"""Entry point: `python -m bitchord_companion`."""
from __future__ import annotations

import logging
import sys

from . import jobs, pipeline, services
from .config import from_env
from .server import App, serve
from .worker import Worker


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    log = logging.getLogger("companion")
    cfg = from_env()
    if len(cfg.token) < 16:
        log.error("Set COMPANION_TOKEN to a random secret of at least 16 characters "
                  "(for example the output of `openssl rand -hex 24`).")
        return 2
    if cfg.host_import:
        if not cfg.inbox_dir.is_dir():
            log.error("INBOX_DIR %s doesn't exist. Mount your incoming folder there.", cfg.inbox_dir)
            return 2
        cfg.queue_dir.mkdir(parents=True, exist_ok=True)
    elif not cfg.music_dir.is_dir():
        log.error("MUSIC_DIR %s doesn't exist. Mount your music library there.", cfg.music_dir)
        return 2
    cfg.work_dir.mkdir(parents=True, exist_ok=True)

    # Host mode never runs the beets in this image: the server's own does the import.
    beets_config = cfg.config_dir / "unused" if cfg.host_import else services.ensure_beets_config(cfg)
    versions = {
        "yt-dlp": services.tool_version(["yt-dlp", "--version"]),
        "beets": "on the server" if cfg.host_import else services.tool_version(["beet", "--version"]),
    }
    store = jobs.JobStore(cfg.config_dir / "jobs.db")
    tools = pipeline.Tools(
        runner=pipeline.Runner(),
        tag=pipeline.mediafile_tag,
        fetch=pipeline.http_fetch,
        rescan=lambda: services.navidrome_rescan(cfg),
    )
    worker = Worker(cfg, beets_config, store, tools)
    worker.start()

    log.info("yt-dlp %s, %s", versions["yt-dlp"], versions["beets"])
    if cfg.host_import:
        log.info("Import: albums go to %s, the server's beets imports them (queue: %s)", cfg.inbox_dir, cfg.queue_dir)
    else:
        log.info("Library: %s (default folder: %s)", cfg.music_dir, cfg.default_folder or "none")
    log.info("Premium cookies: %s", "yes" if cfg.cookies else "no (Opus ~130-160 kbps)")
    log.info("Navidrome rescan: %s", "on" if cfg.navidrome_configured else "off (relies on Navidrome's own scan)")
    log.info("Listening on port %d", cfg.port)
    server = serve(App(cfg, store, worker, versions), cfg.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        worker.stop()
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
