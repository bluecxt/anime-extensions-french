import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from sys import exit
from typing import cast
from urllib.parse import urlparse


def ascii_printable_validator(value: str) -> str:
    cleaned = value.strip()
    if not cleaned:
        raise argparse.ArgumentTypeError(f"invalid value: '{value}' (must not be empty)")

    if not (cleaned.isascii() and cleaned.isprintable()):
        raise argparse.ArgumentTypeError(f"invalid value: '{value}' (must contain only printable ASCII characters)")
    return cleaned

def validate_arguments(args: argparse.Namespace, parser: argparse.ArgumentParser) -> argparse.Namespace:
    # Validate extension class

    ext_class = re.sub(r"[^A-Za-z0-9]", "", args.extname)
    if not ext_class:
        parser.error(f"invalid extname: '{args.extname}' (must contain at least one alphanumeric character)")
    if ext_class[0].isdigit():
        parser.error(f"invalid extname: '{args.extname}' (generated class '{ext_class}' cannot start with a digit)")
    
    # Validate language
    lang = cast(str, args.lang)
    if not re.fullmatch(r"(?:[a-z]{2,3}(?:-[A-Z]{2,3})?|all)", lang):
        parser.error(f"invalid language: '{lang}' (must be a 2- or 3-letter ISO language code)")

    # Validate base URL
    baseurl = urlparse(cast(str, args.baseurl))
    if baseurl.scheme != "https" or not baseurl.netloc:
        parser.error(
            f"invalid baseurl: '{args.baseurl}' (must include scheme 'https://' and a host)"
        )
    args.baseurl = f"{baseurl.scheme}://{baseurl.netloc}{baseurl.path}".rstrip("/")

    # Validate extension repo path
    path = Path(args.path.strip()).resolve()
    if not path.is_dir():
        parser.error(f"invalid path: '{path}' (directory does not exist)")
    elif not (path / "common").is_dir() or not (path / "core").is_dir():
        parser.error(f"invalid path: '{path}' (not a valid extension repo directory)")
    args.path = path

    return args

def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="CLI Tool")

    parser.add_argument(
        "-n", "--extname", type=ascii_printable_validator,
        help="Extension name", required=True
    )
    parser.add_argument(
        "-l", "--lang", type=ascii_printable_validator,
        help="Extension language", required=True,
    )
    parser.add_argument(
        "-u", "--baseurl", type=ascii_printable_validator,
        help="Extension BaseUrl (must be an https URL without / at the end)", required=True,
    )
    parser.add_argument(
        "--nsfw", action="store_true",
        help="Is the extension NSFW"
    )
    parser.add_argument(
        "--path", "-p", type=str,
        help="Path to extension repo directory (defaults to cwd)",
        required=False, default=".",
    )
    parser.add_argument(
        "--activity-scheme", type=ascii_printable_validator,
        help="activity scheme, often https", required=False
    )
    parser.add_argument(
        "--activity-hosts", type=ascii_printable_validator, nargs="+",
        help="activity host (what host will be redirected to the application), multiple host can be added", required=False
    )
    parser.add_argument(
        "--activity-path-patterns", type=ascii_printable_validator, nargs="+",
        help="activity path pattern (what path will be redirected to the application exemple: /catalogue/..*), multiple path can be added", default=[]
    )
    return validate_arguments(parser.parse_args(), parser)

def build_dir_tree(ext_dir: Path, ext_dir_lang: str, ext_dir_name: str) -> Path:
    if ext_dir.exists():
        print(f"Extension directory already exists: '{ext_dir}'")
        exit(1)

    ext_package_dir = (
        ext_dir
        / "src"
        / "eu"
        / "kanade"
        / "tachiyomi"
        / "animeextension"
        / ext_dir_lang
        / ext_dir_name
    )
    ext_package_dir.mkdir(parents=True)
    ( ext_dir / "res" ).mkdir(parents=True)

    print(f"Created extension directory tree: {ext_package_dir}")
    return ext_package_dir

def write_gradle_file(args: argparse.Namespace, ext_dir: Path, ext_class: str):
    gradle_file = (ext_dir / "build.gradle")

    gradle: list[str] = []
    gradle.append("ext {")
    gradle.append(f"\textName = \'{args.extname}\'")
    gradle.append(f"\textClass = \'.{ext_class}\'")
    gradle.append("\textVersionCode = 1")
    if args.nsfw: gradle.append("\tisNsfw = true")
    gradle.append("}\n")
    gradle.append("apply plugin: \"kei.plugins.extension.legacy\"")

    gradle_file.write_text("\n".join(gradle))
    print(f"Created extension gradle file: {gradle_file}")

def write_android_manifest_file(
    args: argparse.Namespace,
    ext_dir: Path,
    ext_dir_lang: str,
    ext_dir_name: str,
    ext_class: str
) -> None:
    manifest_file = (ext_dir / "AndroidManifest.xml")

    ANDROID_NS = "http://schemas.android.com/apk/res/android"
    ET.register_namespace("android", ANDROID_NS)
    
    root = ET.Element("manifest", {"package": f"eu.kanade.tachiyomi.animeextension.{ext_dir_lang}.{ext_dir.name}"})
    application = ET.SubElement(root, "application", {f"{{{ANDROID_NS}}}icon": "@mipmap/ic_launcher"})

    if args.nsfw:
        ET.SubElement(application, "meta-data", {
            f"{{{ANDROID_NS}}}name": "eu.kanade.tachiyomi.animeextension.nsfw",
            f"{{{ANDROID_NS}}}value": "1"
        })

    if args.activity_hosts:
        schem = args.activity_scheme or "https"
        hosts = args.activity_hosts
        path_patterns = args.activity_path_patterns

        activity = ET.SubElement(application, "activity", {
            f"{{{ANDROID_NS}}}name": f".{ext_dir_lang}.{ext_dir_name}.{ext_class}UrlActivity",
            f"{{{ANDROID_NS}}}exported": "true",
            f"{{{ANDROID_NS}}}theme": "@android:style/Theme.NoDisplay",
            f"{{{ANDROID_NS}}}excludeFromRecents": "true",
        })

        intent_filter = ET.SubElement(activity, "intent-filter")

        ET.SubElement(intent_filter, "action", {f"{{{ANDROID_NS}}}name": "android.intent.action.VIEW"})
        ET.SubElement(intent_filter, "category", {f"{{{ANDROID_NS}}}name": "android.intent.category.DEFAULT"})
        ET.SubElement(intent_filter, "category", {f"{{{ANDROID_NS}}}name": "android.intent.category.BROWSABLE"})
        
        ET.SubElement(intent_filter, "data", {f"{{{ANDROID_NS}}}scheme": schem})

        for host in hosts:
            ET.SubElement(intent_filter, "data", {f"{{{ANDROID_NS}}}host": host})

        for pattern in path_patterns:
            ET.SubElement(intent_filter, "data", {f"{{{ANDROID_NS}}}pathPattern": pattern})

    ET.indent(root, space="    ")
    
    tree = ET.ElementTree(root)
    tree.write(manifest_file, encoding="utf-8", xml_declaration=True)

    print(f"Created android manifest file: {manifest_file}")

def write_source_file(
    args: argparse.Namespace,
    ext_package_dir: Path,
    ext_dir_lang: str,
    ext_class: str,
    ext_dir_name: str
) -> None:
    source_file_path = (ext_package_dir / (ext_class + ".kt"))
    source_file: list[str] = []
    source_file.append("// Copyright bluecxt\n// SPDX-License-Identifier: Apache-2.0")
    source_file.append(f"package eu.kanade.tachiyomi.animeextension.{ext_dir_lang}.{ext_dir_name}\n")
    source_file.append("""import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.CommonPreferences
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.Source
import fr.bluecxt.core.tvdb.fetchTvdbMetadata
import fr.bluecxt.core.utils.safeRelativePath
import fr.bluecxt.core.utils.selectFirstLog
import keiyoushi.utils.get
import keiyoushi.utils.post
import keiyoushi.utils.parallelMap
""")
    source_file.append(f"class {ext_class} :")
    source_file.append("\tSource(),")
    source_file.append("\tCommonPreferences {\n")
    source_file.append(f"\toverride val name = \"{args.extname}\"")
    source_file.append(f"\toverride val defaultBaseUrl = \"{args.baseurl}\"\n")
    source_file.append("\toverride val supportedServers = listOf(\"\")")
    source_file.append("\toverride val supportedVoices: Array<String> = arrayOf(\"\")")
    source_file.append(f"\toverride val lang = \"{ext_dir_lang}\"")
    source_file.append("\toverride val supportsLatest = true\n")
    source_file.append("\toverride fun getAnimeUrl(anime: SAnime): String = throw UnsupportedOperationException()\n")
    source_file.append("\t// ============================== Popular ===============================")
    source_file.append("\toverride suspend fun getPopularAnime(page: Int): AnimesPage = throw UnsupportedOperationException()\n")
    source_file.append("\t// ============================== Latest ===============================")
    source_file.append("\toverride suspend fun getLatestUpdates(page: Int): AnimesPage = throw UnsupportedOperationException()\n")
    source_file.append("\t// ============================== Search ===============================")
    source_file.append("\toverride suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = throw UnsupportedOperationException()\n")
    source_file.append("\t// ============================== Anime Details ===============================")
    source_file.append("\toverride suspend fun getAnimeDetails(anime: SAnime): SAnime = throw UnsupportedOperationException()\n")
    source_file.append("\toverride suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = throw UnsupportedOperationException()\n")
    source_file.append("\t// ============================== Season ===============================")
    source_file.append("\toverride suspend fun getSeasonList(anime: SAnime): List<SAnime> = throw UnsupportedOperationException()\n")
    source_file.append("\t// ============================== Episodes ===============================")
    source_file.append("\toverride suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = throw UnsupportedOperationException()\n")
    source_file.append("\t// ============================== Hosters ===============================")
    source_file.append("\toverride suspend fun getHosterList(episode: SEpisode): List<Hoster> = throw UnsupportedOperationException()\n")
    source_file.append("\t// ============================== Videos ===============================")
    source_file.append("\toverride suspend fun getVideoList(hoster: Hoster): List<Video> = throw UnsupportedOperationException()\n")
    source_file.append("\t// ============================== Utils ===============================\n")
    source_file.append("}")

    source_file_path.write_text("\n".join(source_file))
    print(f"Created source file: {source_file_path}")

if __name__ == "__main__":
    args = parse_arguments()

    ext_class = re.sub(r"[^A-Za-z0-9]", "", args.extname)
    ext_dir_name = ext_class.lower()
    ext_dir_lang = args.lang.split("-")[0]
    ext_dir = args.path / "src" / ext_dir_lang / ext_dir_name

    ext_package_dir = build_dir_tree(ext_dir, ext_dir_lang, ext_dir_name)
    
    write_gradle_file(args, ext_dir, ext_class)

    write_android_manifest_file(args, ext_dir, ext_dir_lang, ext_dir_name, ext_class)

    write_source_file(args, ext_package_dir, ext_dir_lang, ext_class, ext_dir_name)
    print("╔═════════════════════╗")
    print("║New extension Created║")
    print("╚═════════════════════╝")

