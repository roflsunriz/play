"""Summarize library wire contracts without emitting account IDs or credentials."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path
from typing import Iterator

from mitmproxy import io


def fields(data: bytes) -> Iterator[tuple[int, int, bytes | int]]:
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
        tag = varint()
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("Invalid protobuf tag")
        if wire == 0:
            value: bytes | int = varint()
        elif wire in (1, 2, 5):
            length = varint() if wire == 2 else (8 if wire == 1 else 4)
            if length > len(data) - position:
                raise ValueError("Truncated protobuf field")
            value = data[position:position + length]
            position += length
        else:
            raise ValueError("Unsupported protobuf wire type")
        yield number, wire, value


def messages(data: bytes, number: int) -> Iterator[bytes]:
    return (v for n, w, v in fields(data) if n == number and w == 2 and isinstance(v, bytes))


def scalar(data: bytes, number: int) -> int | None:
    return next((v for n, w, v in fields(data) if n == number and w == 0 and isinstance(v, int)), None)


def summarize(path: Path) -> dict[str, object]:
    http_counts: Counter[str] = Counter()
    metadata: list[dict[str, object]] = []
    groups: Counter[str] = Counter()
    with path.open("rb") as source:
        for flow in io.FlowReader(source).stream():
            if flow.response is None:
                continue
            endpoint = flow.request.path.split("?", 1)[0]
            category = "/" + endpoint.split("/")[1]
            http_counts[f"{category}:{flow.response.status_code}"] += 1
            credentials = flow.metadata.get("credential_groups", {})
            groups[f"{category}:access-{credentials.get('authorization', 'unknown')}:client-{credentials.get('client-token', 'unknown')}"] += 1
            if endpoint != "/extended-metadata/v0/extended-metadata":
                continue
            feature = flow.request.headers.get("client-feature-id", "absent")
            if not re.fullmatch(r"[A-Za-z_./-]{1,100}", feature):
                feature = "opaque-feature-id"
            requests = list(messages(flow.request.content or b"", 2))
            kinds = Counter(str(scalar(query, 1)) for entity in requests for query in messages(entity, 2))
            statuses: Counter[str] = Counter()
            if flow.response.status_code == 200:
                for array in messages(flow.response.content or b"", 2):
                    kind = scalar(array, 2)
                    for entity in messages(array, 3):
                        header = next(messages(entity, 1), b"")
                        statuses[f"{kind}:{scalar(header, 1)}"] += 1
            metadata.append({
                "feature": feature,
                "requested_entities": len(requests),
                "requested_extensions": dict(sorted(kinds.items())),
                "response_extension_statuses": dict(sorted(statuses.items())),
            })
    return {"http_status_counts": dict(sorted(http_counts.items())), "credential_group_counts": dict(sorted(groups.items())), "metadata_requests": metadata}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = json.dumps(summarize(args.capture), ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result, encoding="utf-8")
    else:
        print(result, end="")


if __name__ == "__main__":
    main()
