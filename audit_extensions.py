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
JAVA_HOME = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk")
JAVA_BIN = os.path.join(JAVA_HOME, "bin", "java")

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
        gradle_cmd = f"JAVA_HOME={JAVA_HOME} ./gradlew :src:fr:{ext_name}:assembleDebug -q"
        result = subprocess.run(gradle_cmd, shell=True, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"   ❌ Échec de la compilation")
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
        for line in output.split("\n"):
            if any(kw in line for kw in ["ERROR", "FAILED", "Exception", "Results"]):
                print(f"      {line.strip()}")
        return False
    else:
        print(f"   ✅ Test réussi")
        return True

def run_all():
    ensure_anitester()

    if shutil.which(JAVA_BIN) is None:
        print(f"❌ Java introuvable à l'emplacement: {JAVA_BIN}")
        sys.exit(1)

    print(f"🚀 [CHEF D'ORCHESTRE] Démarrage de l'audit complet (via anitester.jar)\n")

    extensions_path = "src/fr"
    results = []

    ignored: list[str] = []
    if os.path.exists("ignored_extensions.json"):
        try:
            with open("ignored_extensions.json", "r") as f:
                data = json.load(f)
                if isinstance(data, list):
                    ignored = [str(x) for x in data]
        except Exception as e:
            print(f"⚠️ Erreur chargement ignored_extensions.json: {e}")

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
            if ext_name in ignored:
                status = "⚠️ FAIL (Ignoré)"
                print(f"   ⏭️ Échec ignoré pour {ext_name.upper()}")
            else:
                status = "❌ FAIL"
                
        results.append((ext_name, status))

    print("\n\n" + "="*50)
    print("📊 RÉSUMÉ FINAL DE L'AUDIT")
    print("="*50)
    print(f"{'Extension':<15} | {'Statut':<20}")
    print("-" * 35)
    for name, status in results:
        print(f"{name:<15} | {status:<20}")
    print("="*50)

    if any(status == "❌ FAIL" for _, status in results):
        sys.exit(1)
    sys.exit(0)

if __name__ == "__main__":
    run_all()
