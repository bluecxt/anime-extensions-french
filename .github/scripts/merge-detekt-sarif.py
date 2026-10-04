#!/usr/bin/env python3
import json
from pathlib import Path


def merge_sarif():
    root = Path(".").cwd()
    sarif_files = list(Path(".").glob("**/build/reports/detekt/detekt.sarif"))
    print(f"Found {len(sarif_files)} SARIF files to merge.")

    merged_results = []
    rules_dict = {}
    driver = None

    for sf in sarif_files:
        try:
            with open(sf, "r", encoding="utf-8") as f:
                data = json.load(f)
            runs = data.get("runs", [])
            if not runs:
                continue
            run = runs[0]
            if not driver and "tool" in run and "driver" in run["tool"]:
                driver = run["tool"]["driver"]
            for rule in run.get("tool", {}).get("driver", {}).get("rules", []):
                rules_dict[rule["id"]] = rule

            for r in run.get("results", []):
                for loc in r.get("locations", []):
                    phys_loc = loc.get("physicalLocation", {})
                    art_loc = phys_loc.get("artifactLocation", {})
                    uri = art_loc.get("uri", "")
                    clean_uri = uri.replace("file://" + str(root) + "/", "").replace(
                        "file://", ""
                    )
                    art_loc["uri"] = clean_uri
                merged_results.append(r)
        except Exception as e:
            print(f"Warning: could not process {sf}: {e}")

    if not driver:
        driver = {
            "name": "detekt",
            "fullName": "detekt",
            "informationUri": "https://detekt.dev",
            "rules": [],
        }
    driver["rules"] = list(rules_dict.values())

    merged_sarif = {
        "$schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json",
        "version": "2.1.0",
        "runs": [{"tool": {"driver": driver}, "results": merged_results}],
    }

    output_path = Path("build/reports/detekt/detekt-merged.sarif")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(merged_sarif, f, indent=2)

    print(
        f"Successfully merged {len(merged_results)} results across {len(rules_dict)} rules into {output_path}"
    )


if __name__ == "__main__":
    merge_sarif()
