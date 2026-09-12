"""Extract search response entities without HTTP headers or pagination tokens.

Input: an existing response hex file, or a mitmproxy .flow file. The latter
requires mitmproxy in the Python environment. Review entity contents before commit.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
from typing import Iterator


def entity_fields(data: bytes) -> Iterator[bytes]:
    position = 0

    def varint() -> int:
        nonlocal position
        result = 0
        for index in range(10):
            if position >= len(data):
                raise ValueError("Truncated protobuf varint")
            value = data[position]
            position += 1
            if index == 9 and value > 1:
                raise ValueError("Protobuf varint overflow")
            result |= (value & 127) << (index * 7)
            if value < 128:
                return result
        raise ValueError("Protobuf varint overflow")

    while position < len(data):
        start = position
        tag = varint()
        if tag >> 3 == 0:
            raise ValueError("Invalid protobuf tag")
        wire = tag & 7
        if wire == 0:
            varint()
        elif wire == 2:
            length = varint()
            position += length
        elif wire in (1, 5):
            position += 8 if wire == 1 else 4
        else:
            raise ValueError("Unsupported search response wire type")
        if position > len(data):
            raise ValueError("Truncated protobuf field")
        if tag == 10:
            yield data[start:position]


def response_bytes(path: Path) -> Iterator[bytes]:
    if path.suffix.lower() != ".flow":
        yield bytes.fromhex(path.read_text(encoding="utf-8").strip())
        return
    from mitmproxy import http, io  # Optional: only needed to import a new capture.

    with path.open("rb") as source:
        for flow in io.FlowReader(source).stream():
            if (
                isinstance(flow, http.HTTPFlow)
                and flow.request.method == "GET"
                and flow.request.path.split("?", 1)[0] == "/searchview/v3/search"
                and flow.response is not None
                and flow.response.status_code == 200
            ):
                yield flow.response.content or b""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--response-index", type=int, default=0)
    args = parser.parse_args()
    if args.response_index < 0:
        parser.error("response-index must be non-negative")
    for index, data in enumerate(response_bytes(args.source)):
        if index != args.response_index:
            continue
        filtered = b"".join(entity_fields(data))
        if not filtered:
            raise ValueError("No search entities found; no output was written")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(filtered.hex() + "\n", encoding="ascii")
        print(f"Imported {len(filtered)} bytes; source SHA-256={hashlib.sha256(data).hexdigest()}")
        return
    raise ValueError("Requested search response was not found")


if __name__ == "__main__":
    main()
