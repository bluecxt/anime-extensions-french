import os
import subprocess
import sys
import json
import glob
import shutil
import urllib.request

ANITESTER_URL = "https://github.com/Claudemirovsky/aniyomi-extensions-tester/releases/download/v2.6.1/anitester-min.jar"
SCRIPTS_DIR = os.path.join(".github", "scripts")
ANITESTER_JAR = os.path.join(SCRIPTS_DIR, "anitester.jar")
JAVA_HOME = os.environ.get("JAVA_HOME")
if JAVA_HOME:
    JAVA_BIN = os.path.join(JAVA_HOME, "bin", "java")
else:
    JAVA_BIN = shutil.which("java")

def ensure_anitester():
    """Télécharge anitester.jar s'il n'existe pas."""
    if not os.path.exists(ANITESTER_JAR):
        print(f"📥 Téléchargement de anitester.jar depuis GitHub...")
        os.makedirs(SCRIPTS_DIR, exist_ok=True)
        try:
            urllib.request.urlretrieve(ANITESTER_URL, ANITESTER_JAR)
            print("   ✅ Téléchargement réussi.")
        except Exception as e:
            print(f"   ❌ Erreur de téléchargement: {e}")
            sys.exit(1)

def find_apk(ext_name):
    """Recherche l'APK compilé d'une extension."""
    pattern = f"src/fr/{ext_name}/build/outputs/apk/debug/*.apk"
    apks = glob.glob(pattern)
    apks = [a for a in apks if "androidTest" not in a]
    return apks[0] if apks else None

def run_kotlin_test(ext_name):
    """Teste une extension avec anitester.jar (code Kotlin réel)."""
    apk = find_apk(ext_name)
    if not apk:
        print(f"   ⚠️  Pas d'APK trouvé. Compilation en cours...")
        gradle_cmd = f"./gradlew :src:fr:{ext_name}:assembleDebug -q"
        result = subprocess.run(gradle_cmd, shell=True, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"   ❌ Échec de la compilation")
            if result.stderr:
                print(f"      Erreur: {result.stderr.strip()}")
            return False
        apk = find_apk(ext_name)
        if not apk:
            print(f"   ❌ APK introuvable après compilation")
            return False

    print(f"   📦 APK : {os.path.basename(apk)}")
    cmd = [
        JAVA_BIN, "-jar", ANITESTER_JAR,
        apk,
        "-t", "popular",
        "-c", "2",
        "--timeout", "30",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    output = result.stdout + result.stderr

    output_upper = output.upper()
    failed = False
    if "POPULAR PAGE TEST FAILED" in output_upper:
        failed = True
    elif "COMPLETED" in output_upper:
        after_completed = output_upper.split("COMPLETED")[-1]
        if "FAILED" in after_completed:
            failed = True
    else:
        failed = True

    if failed:
        if "Results" in output:
            for line in output.split("\n"):
                if "Results" in line and "->" in line:
                    count = line.split("->")[-1].strip()
                    try:
                        if int(count) > 0:
                            print(f"   ✅ {count} animes trouvés")
                            return True
                    except ValueError:
                        pass
        print(f"   ❌ Test échoué")
        return False
    else:
        print(f"   ✅ Test réussi")
        return True

def run_all():
    ensure_anitester()

    if JAVA_BIN is None:
        print(f"❌ Java introuvable.")
        sys.exit(1)

    print(f"🚀 [CHEF D'ORCHESTRE] Démarrage de l'audit complet\n")

    extensions_path = "src/fr"
    results = []

    ignored = []
    if os.path.exists("ignored_extensions.json"):
        with open("ignored_extensions.json", "r") as f:
            ignored = json.load(f)

    for ext_name in sorted(os.listdir(extensions_path)):
        ext_dir = os.path.join(extensions_path, ext_name)
        if not os.path.isdir(ext_dir):
            continue

        print(f"--------------------------------------------------")
        print(f"🔎 Audit de : {ext_name.upper()}")

        success = run_kotlin_test(ext_name)
        
        status = ""
        if success:
            status = "✅ PASS"
        else:
            if ext_name.lower() in [x.lower() for x in ignored]:
                status = "⚠️ FAIL (Ignoré)"
                print(f"   ⏭️ Échec ignoré")
            else:
                status = "❌ FAIL"
                
        results.append((ext_name, status))

    print("\n\n" + "="*50)
    print("📊 RÉSUMÉ FINAL DE L'AUDIT")
    print("="*50)
    for name, status in results:
        print(f"{name:<15} | {status:<20}")
    print("="*50)

    write_job_summary(results)

    if any(status == "❌ FAIL" for _, status in results):
        sys.exit(1)
    sys.exit(0)

def write_job_summary(results):
    """Écrit un résumé Markdown dans GitHub Actions Step Summary."""
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return

    total = len(results)
    passed = sum(1 for _, status in results if status == "✅ PASS")
    failed = sum(1 for _, status in results if status == "❌ FAIL")
    ignored = sum(1 for _, status in results if status == "⚠️ FAIL (Ignoré)")

    lines = [
        "## Simulators Audit Summary",
        "",
        f"- Total: **{total}**",
        f"- PASS: **{passed}**",
        f"- FAIL: **{failed}**",
        f"- FAIL (Ignoré): **{ignored}**",
        "",
        "| Extension | Statut |",
        "|---|---|",
    ]

    for name, status in results:
        lines.append(f"| `{name}` | {status} |")

    lines.append("")
    with open(summary_path, "a", encoding="utf-8") as f:
        f.write("\n".join(lines))

if __name__ == "__main__":
    run_all()
