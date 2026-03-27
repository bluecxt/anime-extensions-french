import json
import shutil
import os
import re
from pathlib import Path

REPO_APK_DIR = Path("repo/apk")

# Ensure the directory exists
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

# Path where GitHub Actions downloads the artifacts
ARTIFACTS_DIR = Path.home().joinpath("apk-artifacts")

def get_pkg_name(filename):
    # aniyomi-fr.franime-v14.25.apk -> fr.franime
    m = re.match(r"aniyomi-(.*?)-v.*\.apk", filename)
    return m.group(1) if m else None

# 1. Cleanup: Remove APKs for extensions that no longer exist in src/
# This ensures that if we delete src/fr/otakufr, its APK is also removed from the repo.
if REPO_APK_DIR.exists():
    # Get all active extension names (e.g., 'fr.franime')
    active_extensions = set()
    src_dir = Path("src")
    if src_dir.exists():
        for lang_dir in src_dir.iterdir():
            if lang_dir.is_dir():
                for ext_dir in lang_dir.iterdir():
                    if ext_dir.is_dir():
                        active_extensions.add(f"{lang_dir.name}.{ext_dir.name}")
    
    # Check all APKs in the repo
    for repo_apk in REPO_APK_DIR.glob("*.apk"):
        pkg_name = get_pkg_name(repo_apk.name)
        if pkg_name and pkg_name not in active_extensions:
            print(f"Deleting orphaned APK (extension removed from src): {repo_apk.name}")
            repo_apk.unlink()

# 2. Move new APKs
if ARTIFACTS_DIR.exists():
    for apk in ARTIFACTS_DIR.glob("**/*.apk"):
        if "-release.apk" not in apk.name and "-debug.apk" not in apk.name:
            continue
            
        apk_name = apk.name
        if apk_name.endswith("-debug.apk"):
            apk_name = apk_name.replace("-debug.apk", ".apk")
        elif apk_name.endswith("-release.apk"):
             apk_name = apk_name.replace("-release.apk", ".apk")

        pkg_name = get_pkg_name(apk_name)
        if pkg_name:
            # Remove ANY existing APK for this package to avoid duplicates
            # (e.g. remove v24 if we are adding v25)
            for existing_apk in REPO_APK_DIR.glob(f"aniyomi-{pkg_name}-v*.apk"):
                print(f"Removing old version: {existing_apk.name}")
                existing_apk.unlink()

        dest_path = REPO_APK_DIR.joinpath(apk_name)
        shutil.move(apk, dest_path)
        print(f"Moved {apk.name} to {dest_path}")
else:
    print("No artifacts directory found.")