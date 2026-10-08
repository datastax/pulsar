#!/usr/bin/env python3

import json
import sys
from urllib.parse import urlsplit


def _sanitize_uri(uri: str) -> str:
    split = urlsplit(uri)
    if not split.scheme:
        return uri
    path = split.hostname or ""
    if split.path:
        path += split.path
    if split.query:
        path += f"?{split.query}"
    return path.rstrip("/") or uri


def main() -> None:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <sarif-file>", file=sys.stderr)
        sys.exit(1)

    sarif_path = sys.argv[1]
    with open(sarif_path, encoding="utf-8") as fh:
        sarif = json.load(fh)

    rewritten = 0
    for run in sarif.get("runs", []):
        for result in run.get("results", []):
            for location in result.get("locations", []):
                artifact = location.get("physicalLocation", {}).get("artifactLocation", {})
                if "uri" in artifact:
                    artifact["uri"] = _sanitize_uri(artifact["uri"])
                    rewritten += 1

    with open(sarif_path, "w", encoding="utf-8") as fh:
        json.dump(sarif, fh, indent=2)

    print(f"Sanitized {rewritten} artifactLocation URI(s) in {sarif_path}")


if __name__ == "__main__":
    main()
