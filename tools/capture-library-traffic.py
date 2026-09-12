"""Record library responses only. Raw output belongs in the ignored captures folder."""

from __future__ import annotations

import json
import os
import hashlib
from pathlib import Path

from mitmproxy import http, io


class LibraryCapture:
    def __init__(self) -> None:
        self.directory = Path(os.environ["PLAY_LIBRARY_CAPTURE_DIR"])
        self.directory.mkdir(parents=True, exist_ok=True)
        self.output = (self.directory / "library.flow").open("ab")
        self.writer = io.FlowWriter(self.output)
        self.counts: dict[str, dict[str, int]] = {}
        self.credential_groups: dict[str, dict[bytes, int]] = {}

    def response(self, flow: http.HTTPFlow) -> None:
        path = flow.request.path.split("?", 1)[0]
        category = next(
            (prefix for prefix in ("/extended-metadata/", "/collection/", "/playlist/", "/searchview/") if path.startswith(prefix)),
            None,
        )
        if category is None or flow.response is None:
            return
        recorded = flow.copy()
        groups: dict[str, int] = {}
        for name in ("authorization", "client-token"):
            value = flow.request.headers.get(name)
            if value is None:
                groups[name] = 0
                continue
            digest = hashlib.sha256(value.encode()).digest()
            known = self.credential_groups.setdefault(name, {})
            groups[name] = known.setdefault(digest, len(known) + 1)
        recorded.metadata["credential_groups"] = groups
        recorded.metadata["request_header_names"] = list(flow.request.headers.keys())
        allowed = {
            "accept", "content-type", "content-encoding", "accept-language", "user-agent",
            "spotify-app-version", "app-platform", "x-dynamic-device-context", "x-client-id",
            "client-feature-id", "time-zone", "cache-control", "x-accept-list-items",
            "spotify-accept-geoblock", "spotify-applied-lenses", "spotify-apply-lenses",
            "spotify-dsa-mode-enabled", "spotify-playlist-sync-reason",
        }
        for headers in (recorded.request.headers, recorded.response.headers):
            for name in list(headers):
                if name.lower() not in allowed:
                    del headers[name]
        self.writer.add(recorded)
        self.output.flush()
        statuses = self.counts.setdefault(category, {})
        status = str(flow.response.status_code)
        statuses[status] = statuses.get(status, 0) + 1
        (self.directory / "summary.json").write_text(json.dumps(self.counts, indent=2), encoding="utf-8")
    def done(self) -> None:
        self.output.close()


addons = [LibraryCapture()]
