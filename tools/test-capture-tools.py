"""Offline checks for capture filtering and credential redaction (requires mitmproxy)."""

from __future__ import annotations

import importlib.util
import io as stdio
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from mitmproxy import connection, http, io


def load_tool(name: str):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(f"{name}.py"))
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CaptureToolsTest(unittest.TestCase):
    def test_license_payloads_are_not_saved_including_failed_responses(self) -> None:
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"PLAY_LIBRARY_CAPTURE_DIR": directory}):
            capture = load_tool("capture-library-traffic").addons[0]
            request_body = b"synthetic-license-request"
            response_body = b"synthetic-license-response"
            try:
                for status in (200, 403):
                    flow = self.flow("/widevine-license/v1/audio", status, request_body, response_body)
                    capture.response(flow)
                    self.assertEqual(request_body, flow.request.content)
                    self.assertEqual(response_body, flow.response.content)
            finally:
                capture.done()
            raw = Path(directory, "library.flow").read_bytes()
            self.assertNotIn(request_body, raw)
            self.assertNotIn(response_body, raw)
            recorded = list(io.FlowReader(stdio.BytesIO(raw)).stream())
            self.assertEqual([200, 403], [flow.response.status_code for flow in recorded])
            for flow in recorded:
                self.assertEqual([len(request_body), len(response_body)], flow.metadata["payload_lengths"])
                self.assertEqual(b"", flow.request.content)
                self.assertEqual(b"", flow.response.content)

    def test_playback_metadata_is_retained_for_the_summary(self) -> None:
        endpoints = ["storage-resolve", "manifests", "track-playback", "melody",
                     "connect-state", "sequence-proxy", "padme"]
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"PLAY_LIBRARY_CAPTURE_DIR": directory}):
            capture = load_tool("capture-library-traffic").addons[0]
            try:
                for endpoint in endpoints:
                    capture.response(self.flow(f"/{endpoint}/v1/test", 200, b"request", b"response"))
            finally:
                capture.done()
            saved = Path(directory, "library.flow")
            with saved.open("rb") as source:
                recorded = list(io.FlowReader(source).stream())
            self.assertEqual(len(endpoints), len(recorded))
            for flow in recorded:
                self.assertEqual(b"request", flow.request.content)
                self.assertEqual(b"response", flow.response.content)
            summary = load_tool("inspect-library-capture").summarize(saved)
            self.assertEqual({f"/{endpoint}:200": 1 for endpoint in endpoints}, summary["http_status_counts"])

    def test_authentication_and_unrelated_traffic_are_excluded(self) -> None:
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"PLAY_LIBRARY_CAPTURE_DIR": directory}):
            capture = load_tool("capture-library-traffic").addons[0]
            try:
                for endpoint in ("/v3/login", "/v4/login", "/api/token", "/unrelated/", "/playlist-other/"):
                    capture.response(self.flow(endpoint, 200, b"synthetic-secret", b"synthetic-secret"))
            finally:
                capture.done()
            self.assertEqual(b"", Path(directory, "library.flow").read_bytes())

    @staticmethod
    def flow(endpoint: str, status: int, request: bytes, response: bytes) -> http.HTTPFlow:
        flow = http.HTTPFlow(
            connection.Client(peername=("127.0.0.1", 1), sockname=("127.0.0.1", 2)),
            connection.Server(address=("example.com", 443)),
        )
        flow.request = http.Request.make("POST", "https://example.com" + endpoint, request)
        flow.response = http.Response.make(status, response)
        return flow

    def test_search_entities_preserve_fixture_and_drop_control_fields(self) -> None:
        importer = load_tool("import-search-capture")
        fixture = Path(__file__).parent.parent / "app/src/test/resources/captures/search-response.hex"
        entities = bytes.fromhex(fixture.read_text())
        self.assertEqual(entities, b"".join(importer.entity_fields(b"\x1a\x05token" + entities)))

    def test_truncated_response_is_rejected(self) -> None:
        importer = load_tool("import-search-capture")
        for broken in (b"\x0a\x08short", b"\x0a\x80", b"\x00"):
            with self.assertRaises(ValueError):
                list(importer.entity_fields(broken))

    def test_recorded_flow_removes_credentials_without_changing_live_headers(self) -> None:
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"PLAY_LIBRARY_CAPTURE_DIR": directory}):
            capture = load_tool("capture-library-traffic").addons[0]
            flow = http.HTTPFlow(
                connection.Client(peername=("127.0.0.1", 1), sockname=("127.0.0.1", 2)),
                connection.Server(address=("example.com", 443)),
            )
            flow.request = http.Request.make("GET", "https://example.com/playlist/v2/user/synthetic/rootlist", headers={
                "Authorization": "Bearer synthetic-access", "Client-Token": "synthetic-client",
                "Cookie": "synthetic-cookie", "Accept": "application/protobuf", "Time-Zone": "Asia/Tokyo",
            })
            flow.response = http.Response.make(200, b"\x08\x01", {"Set-Cookie": "synthetic-response-cookie"})
            try:
                capture.response(flow)
                capture.response(flow)
            finally:
                capture.done()
            self.assertEqual("Bearer synthetic-access", flow.request.headers["Authorization"])
            self.assertIn("Cookie", flow.request.headers)
            self.assertIn("Set-Cookie", flow.response.headers)
            raw = Path(directory, "library.flow").read_bytes()
            self.assertNotIn(b"synthetic-access", raw)
            self.assertNotIn(b"synthetic-client", raw)
            self.assertNotIn(b"synthetic-cookie", raw)
            self.assertNotIn(b"synthetic-response-cookie", raw)
            recorded = list(io.FlowReader(stdio.BytesIO(raw)).stream())
            self.assertEqual(2, len(recorded))
            self.assertEqual("Asia/Tokyo", recorded[0].request.headers["Time-Zone"])
            self.assertEqual(b"\x08\x01", recorded[0].response.content)
            self.assertEqual({"authorization": 1, "client-token": 1}, recorded[0].metadata["credential_groups"])
            self.assertEqual(recorded[0].metadata["credential_groups"], recorded[1].metadata["credential_groups"])


if __name__ == "__main__":
    unittest.main()
