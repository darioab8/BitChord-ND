"""Settings, read once from the environment.

Every path is inside the container; docker-compose maps them to the host.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


BEETS = "beets"
HOST = "host"


@dataclass(frozen=True)
class Config:
    #: Shared secret the app (and the web page) must send as a Bearer token.
    token: str
    #: "host": tagged album folders go to inbox_dir and the user's own beets,
    #: on the host, imports each one (see companion/host/). "beets": the
    #: companion imports into music_dir with the beets inside the container.
    import_mode: str
    #: Host mode: where downloaded albums wait for the host's beets.
    inbox_dir: Path
    #: Host mode: seconds to wait for the host importer to pick a request up.
    host_pickup_timeout: float
    #: The music library Navidrome reads. Albums land in a folder under it.
    music_dir: Path
    #: Folder under music_dir used when a request doesn't name one.
    default_folder: str
    #: Where the companion keeps its job database, beets library and logs.
    config_dir: Path
    #: Scratch space for downloads before beets moves them into the library.
    work_dir: Path
    #: Netscape cookies.txt from a YouTube Music (Premium) session, optional.
    cookies_file: Path
    #: A beets config to use instead of the generated one, optional.
    beets_config: Path | None
    #: Optional: ask Navidrome to rescan when an album is in. Needs an admin user.
    navidrome_url: str
    navidrome_user: str
    navidrome_password: str
    port: int

    @property
    def host_import(self) -> bool:
        return self.import_mode == HOST

    @property
    def queue_dir(self) -> Path:
        """Host mode: requests for the host importer, and its answers."""
        return self.config_dir / "queue"

    @property
    def cookies(self) -> Path | None:
        return self.cookies_file if self.cookies_file.is_file() else None

    @property
    def navidrome_configured(self) -> bool:
        return bool(self.navidrome_url and self.navidrome_user)


def from_env(env: dict[str, str] | None = None) -> Config:
    e = os.environ if env is None else env
    config_dir = Path(e.get("CONFIG_DIR", "/config"))
    beets = e.get("BEETS_CONFIG", "").strip()
    mode = e.get("IMPORT_MODE", BEETS).strip().lower()
    if mode not in (BEETS, HOST):
        raise ValueError(f"IMPORT_MODE must be '{HOST}' or '{BEETS}', not '{mode}'")
    return Config(
        token=e.get("COMPANION_TOKEN", "").strip(),
        import_mode=mode,
        inbox_dir=Path(e.get("INBOX_DIR", "/incoming")),
        host_pickup_timeout=float(e.get("HOST_PICKUP_TIMEOUT", "120")),
        music_dir=Path(e.get("MUSIC_DIR", "/music")),
        default_folder=e.get("DEFAULT_FOLDER", "YouTube").strip(),
        config_dir=config_dir,
        work_dir=Path(e.get("WORK_DIR", str(config_dir / "work"))),
        cookies_file=Path(e.get("COOKIES_FILE", str(config_dir / "cookies.txt"))),
        beets_config=Path(beets) if beets else None,
        navidrome_url=e.get("NAVIDROME_URL", "").strip().rstrip("/"),
        navidrome_user=e.get("NAVIDROME_USER", "").strip(),
        navidrome_password=e.get("NAVIDROME_PASSWORD", ""),
        port=int(e.get("PORT", "8765")),
    )
