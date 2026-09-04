import hashlib
import json
import os
import re
import struct
import subprocess
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.animeextension.nsfw' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
LANGUAGE_REGEX = re.compile(r"aniyomi-([^.]+)")

*_, ANDROID_BUILD_TOOLS = (Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir()
REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"

REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

with open("output.json", encoding="utf-8") as f:
    inspector_data = json.load(f)

index_min_data = []

def extract_apk_fingerprint(apk_path):
    with open(apk_path, "rb") as f:
        data = f.read()
    eocd_pos = data.rfind(b"\x50\x4b\x05\x06")
    if eocd_pos == -1:
        return ""
    cd_size, cd_offset = struct.unpack("<II", data[eocd_pos+12:eocd_pos+20])
    if cd_offset < 16 or data[cd_offset-16:cd_offset] != b"APK Sig Block 42":
        return ""
    block_size_trailer = struct.unpack("<Q", data[cd_offset-24:cd_offset-16])[0]
    block_start = cd_offset - 8 - block_size_trailer
    block_data = data[block_start+8:cd_offset-24]
    offset = 0
    while offset < len(block_data):
        pair_len = struct.unpack("<Q", block_data[offset:offset+8])[0]
        pair_id = struct.unpack("<I", block_data[offset+8:offset+12])[0]
        pair_value = block_data[offset+12:offset+8+pair_len]
        if pair_id in (0x7109871a, 0xf05368c0):
            signer_data = pair_value[8:]
            spos = 4 + struct.unpack("<I", signer_data[4:8])[0] + 4
            cert1_len = struct.unpack("<I", signer_data[spos:spos+4])[0]
            spos += 4
            cert1_bytes = signer_data[spos:spos+cert1_len]
            return hashlib.sha256(cert1_bytes).hexdigest()
        offset += 8 + pair_len
    return ""

signing_fingerprint = ""

for apk in REPO_APK_DIR.iterdir():
    if not signing_fingerprint:
        signing_fingerprint = extract_apk_fingerprint(apk)
    badging = subprocess.check_output(
        [
            ANDROID_BUILD_TOOLS / "aapt",
            "dump",
            "--include-meta-data",
            "badging",
            apk,
        ]
    ).decode()

    package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
    package_name = PACKAGE_NAME_REGEX.search(package_info).group(1)
    application_icon = APPLICATION_ICON_320_REGEX.search(badging).group(1)

    with ZipFile(apk) as z, z.open(application_icon) as i, (
        REPO_ICON_DIR / f"{package_name}.png"
    ).open("wb") as f:
        f.write(i.read())

    language = LANGUAGE_REGEX.search(apk.name).group(1)
    sources = inspector_data[package_name]

    if len(sources) == 1:
        source_language = sources[0]["lang"]

        if (
            source_language != language
            and source_language not in {"all", "other"}
            and language not in {"all", "other"}
        ):
            language = source_language

    common_data = {
        "name": APPLICATION_LABEL_REGEX.search(badging).group(1),
        "pkg": package_name,
        "apk": apk.name,
        "lang": language,
        "code": int(VERSION_CODE_REGEX.search(package_info).group(1)),
        "version": VERSION_NAME_REGEX.search(package_info).group(1),
        "nsfw": int(IS_NSFW_REGEX.search(badging).group(1)),
    }
    min_data = {
        **common_data,
        "sources": [],
    }

    for source in sources:
        min_data["sources"].append(
            {
                "name": source["name"],
                "lang": source["lang"],
                "id": source["id"],
                "baseUrl": source["baseUrl"],
                "versionId": source["versionId"],
            }
        )

    index_min_data.append(min_data)

with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as index_file:
    json.dump(index_min_data, index_file, ensure_ascii=False, separators=(",", ":"))

repo_name = os.environ.get("REPO_NAME")
if repo_name:
    channel = os.environ.get("CHANNEL", "dev")
    short_name = os.environ.get("SHORT_NAME", "repo")
    author = os.environ.get("AUTHOR", "bluecxt")
    icon_url = f"https://bluecxt.github.io/anime-extensions-french/{channel}/icon.png"
    repo_json_data = {
        "meta": {
            "name": repo_name,
            "shortName": short_name,
            "website": "https://github.com/bluecxt/anime-extensions-french",
            "signingKeyFingerprint": signing_fingerprint,
            "iconUrl": icon_url,
            "author": author,
        }
    }
    with REPO_DIR.joinpath("repo.json").open("w", encoding="utf-8") as repo_file:
        json.dump(repo_json_data, repo_file, ensure_ascii=False, separators=(",", ":"))
    print(f"Successfully generated repo.json with fingerprint: {signing_fingerprint}")
