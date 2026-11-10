#!/usr/bin/env python3
"""Validate tag release metadata. stdout is JSON; failure is stderr + exit 1."""
import argparse
import json
from pathlib import Path
import re


NUMBER = r"(?:0|[1-9][0-9]*)"
VERSION = re.compile(rf"{NUMBER}\.{NUMBER}\.{NUMBER}(?:-(alpha|beta|rc)\.{NUMBER})?")
HEADINGS = ("Added", "Changed", "Fixed", "Breaking", "Data-Permissions-Compatibility",
            "Tested-KnownIssues", "Update")


def version_code(value):
    if not re.fullmatch(r"[1-9][0-9]{0,9}", value) or int(value) > 2100000000:
        raise ValueError("versionCode must be canonical decimal in 1..2100000000")
    return int(value)


def read_properties(path):
    # Strict subset of Java Properties shared with Gradle: no escapes/continuations.
    properties = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        match = re.fullmatch(r"(versionName|versionCode)=([A-Za-z0-9.+;-]+)", line)
        if not match or match[1] in properties:
            raise ValueError("metadata requires unique versionName=value and versionCode=value lines")
        properties[match[1]] = match[2]
    if set(properties) != {"versionName", "versionCode"}:
        raise ValueError("metadata requires versionName and versionCode")
    return properties


def release_notes(path, version):
    text = path.read_text(encoding="utf-8")
    headings = list(re.finditer(r"^## \[([^\]\n]+)\]$", text, re.MULTILINE))
    matches = [(index, heading) for index, heading in enumerate(headings) if heading[1] == version]
    if len(matches) != 1:
        raise ValueError("changelog must contain exactly one matching ## [version] heading")
    index, heading = matches[0]
    end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
    section = text[heading.end():end].strip()
    parts = re.split(r"^### (.+)$", section, flags=re.MULTILINE)
    if parts[0].strip() or parts[1::2] != list(HEADINGS):
        raise ValueError("changelog must contain the required headings once, in documented order")
    substantive = False
    for title, body in zip(parts[1::2], parts[2::2]):
        lines = [line.strip() for line in body.splitlines() if line.strip()]
        if not lines or any(not line.startswith("- ") or not line[2:].strip() for line in lines):
            raise ValueError(f"changelog {title} requires nonempty bullet items")
        values = [line[2:].strip().rstrip(".").casefold() for line in lines]
        if any(value in {"todo", "tbd", "pending", "n/a"}
               or not any(char.isalnum() for char in value) for value in values):
            raise ValueError(f"changelog {title} contains placeholder items")
        if title in HEADINGS[:3] and any(value != "none" for value in values):
            substantive = True
    if not substantive:
        raise ValueError("Added/Changed/Fixed must describe at least one substantive change")
    return f"## [{version}]\n\n{section}\n"


def validate(tag, ref, metadata, changelog, previous_codes):
    properties = read_properties(metadata)
    version = properties["versionName"]
    match = VERSION.fullmatch(version)
    if not match:
        raise ValueError("versionName must be X.Y.Z or X.Y.Z-(alpha|beta|rc).N without leading zeros")
    if tag != "v" + version or ref != "refs/tags/" + tag:
        raise ValueError("tag/ref must exactly match v + versionName and refs/tags/ + tag")
    code = version_code(properties["versionCode"])
    if any(code <= version_code(previous) for previous in previous_codes):
        raise ValueError("versionCode must exceed every published release versionCode")
    return {"schema_version": 1, "tag": tag, "version": version,
            "version_code": code, "prerelease": match[1] is not None,
            "notes": release_notes(changelog, version)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--changelog", type=Path, required=True)
    parser.add_argument("--previous-version-code", action="append", default=[])
    args = parser.parse_args()
    try:
        result = validate(args.tag, args.ref, args.metadata, args.changelog, args.previous_version_code)
    except (ValueError, KeyError, IndexError, OSError) as error:
        parser.exit(1, f"release validation failed: {error}\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
