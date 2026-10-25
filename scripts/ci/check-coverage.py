#!/usr/bin/env python3
"""Require source-complete coverage for every implemented production module."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

BACKEND = {"domain": 95, "application": 95, "adapters": 90, "bootstrap": 90}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def threshold(covered, total, minimum, label, allow_empty=False):
    require(isinstance(covered, int) and isinstance(total, int)
            and 0 <= covered <= total, f"Invalid counters: {label}")
    require(total > 0 or allow_empty, f"Empty coverage: {label}")
    require(total == 0 or covered * 100 >= total * minimum,
            f"Coverage below {minimum}%: {label} ({covered}/{total})")
    return f"{label}: {covered}/{total}"


def counter(element, kind):
    counters = [item for item in element.findall("counter") if item.get("type") == kind]
    if not counters and kind == "BRANCH":
        lines = element.findall(".//sourcefile/line")
        # JaCoCo omits zero-total branch counters for straight-line modules.
        if lines and not element.findall(".//counter[@type='BRANCH']") and all(
                line.get("mb") == "0" and line.get("cb") == "0" for line in lines):
            return 0, 0
    require(len(counters) == 1, f"Missing or duplicate {kind} counter")
    covered, missed = int(counters[0].get("covered")), int(counters[0].get("missed"))
    require(covered >= 0 and missed >= 0, f"Negative {kind} counter")
    return covered, covered + missed


def backend(root):
    results = []
    for name, minimum in BACKEND.items():
        module = root / "backend" / name
        source_root = module / "src/main/java"
        sources = {file.relative_to(source_root).as_posix() for file in source_root.rglob("*.java")}
        if not sources:
            continue  # Empty build scaffolds make no feature coverage claim.
        report = module / "target/site/jacoco/jacoco.xml"
        require(report.is_file(), f"Missing coverage report: {name}")
        tree = ET.parse(report).getroot()
        reported = set()
        for package in tree.findall("package"):
            prefix = package.get("name", "")
            for item in list(package.findall("sourcefile")) + list(package.findall("class")):
                filename = item.get("sourcefilename") if item.tag == "class" else item.get("name")
                if filename:
                    reported.add(f"{prefix}/{filename}".lstrip("/"))
        require(sources <= reported, f"Unreported production sources: {name}")
        for kind, limit in (("LINE", minimum), ("BRANCH", 90)):
            covered, total = counter(tree, kind)
            results.append(threshold(covered, total, limit, f"{name} {kind}", kind == "BRANCH"))
    return results


def frontend(root):
    source_root = root / "frontend/src"
    sources = {file.resolve() for file in source_root.rglob("*.ts")
               if not file.name.endswith((".spec.ts", ".d.ts"))}
    if not sources:
        return []
    # Only files individually named in generator provenance may be excluded.
    generated = source_root / "app/shared/generated"
    manifest = generated / ".openapi-generator/FILES"
    version = generated / ".openapi-generator/VERSION"
    if manifest.is_file() and version.is_file() and version.read_text().strip():
        exclusions = {(generated / line.strip()).resolve() for line in manifest.read_text().splitlines()}
        require(all(file.is_relative_to(generated.resolve()) for file in exclusions),
                "Generated manifest escapes its directory")
        sources -= exclusions
    if not sources:
        return []  # Generated-only contract scaffolding has no handwritten coverage claim.
    report = root / "frontend/coverage/coverage-final.json"
    require(report.is_file(), "Missing Angular coverage-final.json")
    data = json.loads(report.read_text())
    require(isinstance(data, dict) and data, "Empty Angular coverage report")
    reported = {}
    for key, entry in data.items():
        file = Path(key)
        if not file.is_absolute():
            file = root / "frontend" / file
        require(file.resolve() not in reported, "Duplicate Angular source entry")
        reported[file.resolve()] = entry
    require(sources <= reported.keys(), "Unreported Angular production sources")
    lines, covered_lines, branches, covered_branches = 0, 0, 0, 0
    for file in sources:
        entry = reported[file]
        statements, statement_map = entry["s"], entry["statementMap"]
        counts, branch_map = entry["b"], entry["branchMap"]
        require(statements.keys() == statement_map.keys() and counts.keys() == branch_map.keys(),
                "Incomplete Angular coverage maps")
        by_line = {}
        for key, hits in statements.items():
            require(isinstance(hits, int) and hits >= 0, "Invalid Angular statement hits")
            line = statement_map[key]["start"]["line"]
            require(isinstance(line, int) and line > 0, "Invalid Angular source line")
            by_line[line] = max(by_line.get(line, 0), hits)
        lines += len(by_line)
        covered_lines += sum(hits > 0 for hits in by_line.values())
        for key, hits in counts.items():
            require(isinstance(hits, list) and hits
                    and len(hits) == len(branch_map[key]["locations"]), "Incomplete Angular branches")
            require(all(isinstance(hit, int) and hit >= 0 for hit in hits), "Invalid Angular branch hits")
            branches += len(hits)
            covered_branches += sum(hit > 0 for hit in hits)
    return [threshold(covered_lines, lines, 90, "Angular LINE"),
            threshold(covered_branches, branches, 90, "Angular BRANCH", True)]


def validate(root):
    return backend(root) + frontend(root)


if __name__ == "__main__":
    try:
        for result in validate(Path(__file__).resolve().parents[2]):
            print(result)
    except (ValueError, TypeError, KeyError, OSError, ET.ParseError) as failure:
        # Report only gate failures from synthetic report structure, never source content.
        print(f"Coverage verification failed: {type(failure).__name__}: {failure}", file=sys.stderr)
        sys.exit(1)
