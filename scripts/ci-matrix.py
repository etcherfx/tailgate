# /// script
# requires-python = ">=3.11"
# ///
"""Prints the CI matrices as GitHub step outputs, from targets.toml.

    uv run scripts/ci-matrix.py [--all-runtime] >> "$GITHUB_OUTPUT"

build:   the UI nodes split into groups, one parallel build job each.
runtime: the nodes to launch with scripts/runtime-test.py. By default a smoke set that covers
         each loader's newest release and every boundary where the UI code changes (the
         `//? if` cutoffs and the [compile-as] nodes); --all-runtime tests every node.
"""
import json
import os
import sys
import tomllib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GROUP_SIZE = {"fabric": 8, "forge": 5, "neoforge": 6}

SMOKE = [
    # Fabric: 26.2 moved setScreen to Gui; 26.1 render extraction; 1.20.2 background and ServerData.Type;
    # 1.20 GuiGraphics; 1.19.4 focus; 1.19.3 Button.builder; 1.19 Component.literal; 1.17 static draws;
    # 1.16 PoseStack; 1.14.4 first Mojang mappings; 1.14 compiled as 1.14.4.
    "26.3-fabric", "26.1-fabric", "1.21.11-fabric", "1.20.2-fabric", "1.20.1-fabric", "1.19.4-fabric",
    "1.19.3-fabric", "1.19-fabric", "1.18.2-fabric", "1.17.1-fabric", "1.16.5-fabric", "1.15.2-fabric",
    "1.14.4-fabric", "1.14-fabric",
    # Forge: 1.21.6 EventBus 7; 1.19 ScreenEvent.Init; 1.18 InitScreenEvent; 1.16.1 and 1.14.2 compiled as
    # neighbours; 1.13.2 and older use the legacy screens.
    "26.3-forge", "26.1-forge", "1.21.6-forge", "1.21.5-forge", "1.20.1-forge", "1.19.2-forge",
    "1.18.2-forge", "1.17.1-forge", "1.16.5-forge", "1.16.1-forge", "1.14.4-forge", "1.14.2-forge",
    "1.13.2-forge", "1.12.2-forge", "1.10.2-forge", "1.8.9-forge", "1.7.10-forge",
    "26.3-neoforge", "26.1-neoforge", "1.21.1-neoforge", "1.20.2-neoforge",
]


def version_key(mc):
    return tuple(int(part) for part in mc.split("."))


def nodes():
    path = os.path.join(ROOT, "targets.toml")
    try:
        with open(path, "rb") as f:
            targets = tomllib.load(f)
    except (OSError, tomllib.TOMLDecodeError) as e:
        sys.exit(f"ci-matrix.py: can't read {path}: {e}")
    by_loader = {
        "fabric": list(targets["fabric"]["versions"]),
        "forge": list(targets["forge"]),
        "neoforge": list(targets["neoforge"]),
    }
    return {loader: sorted(versions, key=version_key, reverse=True) for loader, versions in by_loader.items()}


def main():
    all_runtime = "--all-runtime" in sys.argv[1:]
    releases = nodes()
    known = {f"{mc}-{loader}" for loader, versions in releases.items() for mc in versions}

    build = []
    for loader, versions in releases.items():
        size = GROUP_SIZE[loader]
        for start in range(0, len(versions), size):
            chunk = versions[start:start + size]
            build.append({
                "name": f"{loader} {chunk[-1]}-{chunk[0]}" if len(chunk) > 1 else f"{loader} {chunk[0]}",
                "id": f"{loader}-{start // size}",
                "nodes": ",".join(f"{mc}-{loader}" for mc in chunk),
            })

    unknown = [node for node in SMOKE if node not in known]
    if unknown:
        sys.exit(f"ci-matrix.py: smoke nodes missing from targets.toml: {', '.join(unknown)}")
    runtime = sorted(known, key=lambda n: (n.rpartition("-")[2], version_key(n.rpartition("-")[0]))) if all_runtime else SMOKE

    print(f"build={json.dumps(build)}")
    print(f"runtime={json.dumps(runtime)}")


if __name__ == "__main__":
    main()
