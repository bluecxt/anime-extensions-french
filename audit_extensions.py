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
AUDIT_APK_DIR = os.environ.get("AUDIT_APK_DIR")
AUDIT_SKIP_GRADLE_BUILD = os.environ.get("AUDIT_SKIP_GRADLE_BUILD", "0") == "1"
JAVA_HOME = os.environ.get("JAVA_HOME")
if JAVA_HOME:
    JAVA_BIN = os.path.join(JAVA_HOME, "bin", "java")
else:
    JAVA_BIN = shutil.which("java")

def ensure_anitester():
    if not os.path.exists(ANITESTER_JAR):
        print(f"📥 Téléchargement de anitester.jar...")
        os.makedirs(SCRIPTS_DIR, exist_ok=True)
        try:
            urllib.request.urlretrieve(ANITESTER_URL, ANITESTER_JAR)
        except Exception as e:
            print(f"   ❌ Erreur: {e}")
            sys.exit(1)

def find_apk(ext_name):
    patterns = []
    if AUDIT_APK_DIR:
        patterns.append(os.path.join(AUDIT_APK_DIR, f"src/fr/{ext_name}/build/outputs/apk/debug/*.apk"))
    patterns.append(f"src/fr/{ext_name}/build/outputs/apk/debug/*.apk")
    for pattern in patterns:
        apks = glob.glob(pattern, recursive=True)
        apks = [a for a in apks if "androidTest" not in a]
        if apks: return apks[0]
    return None

def run_kotlin_test(ext_name):
    apk = find_apk(ext_name)
    if not apk:
        if AUDIT_SKIP_GRADLE_BUILD: return False, "APK introuvable"
        print(f"   ⚠️  Compilation de {ext_name}...")
        subprocess.run(f"./gradlew :src:fr:{ext_name}:assembleDebug -q", shell=True)
        apk = find_apk(ext_name)
        if not apk: return False, "Échec compilation"

    print(f"   📦 APK : {os.path.basename(apk)}")
    cmd = [JAVA_BIN, "-jar", ANITESTER_JAR, apk, "-t", "popular", "-c", "2", "--timeout", "30"]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        output = result.stdout + result.stderr
    except subprocess.TimeoutExpired:
        return False, "Timeout global"

    output_upper = output.upper()
    
    # Correction de la détection de succès
    # Si on voit "COMPLETED POPULAR PAGE TEST" et PAS de message d'erreur fatal, c'est réussi
    if "COMPLETED POPULAR PAGE TEST" in output_upper:
        if "FAILED" not in output_upper.split("COMPLETED")[-1]:
            return True, "Test Popular réussi"

    # Fallback sur la détection par nombre de résultats
    if "RESULTS" in output_upper:
        for line in output.split("\n"):
            if "Results" in line and "->" in line:
                try:
                    count = int(line.split("->")[-1].strip())
                    if count > 0: return True, f"{count} animes trouvés"
                except: pass

    reason = "Échec du test"
    if "403" in output_upper: reason = "Erreur HTTP 403"
    elif "404" in output_upper: reason = "Erreur HTTP 404"
    elif "TIMEOUT" in output_upper: reason = "Timeout"
    elif "CLOUDFLARE" in output_upper: reason = "Cloudflare"
    
    return False, reason

def run_all():
    ensure_anitester()
    print(f"🚀 [CHEF D'ORCHESTRE] Démarrage de l'audit (Java 17)\n")
    extensions_path = "src/fr"
    results = []
    ignored = []
    if os.path.exists("ignored_extensions.json"):
        with open("ignored_extensions.json", "r") as f: ignored = json.load(f)

    for ext_name in sorted(os.listdir(extensions_path)):
        if not os.path.isdir(os.path.join(extensions_path, ext_name)): continue
        
        print(f"--------------------------------------------------")
        print(f"🔎 Audit de : {ext_name.upper()}")
        success, reason = run_kotlin_test(ext_name)
        
        status = "✅ PASS" if success else "❌ FAIL"
        if not success and ext_name.lower() in [x.lower() for x in ignored]:
            status = "⚠️ FAIL (Ignoré)"
        
        print(f"   Resultat : {status} - {reason}")
        results.append((ext_name, status, reason))

    print("\n\n" + "="*80)
    print(f"{'Extension':<15} | {'Statut':<15} | {'Raison'}")
    print("="*80)
    for name, status, reason in results:
        print(f"{name:<15} | {status:<15} | {reason}")
    print("="*80)

if __name__ == "__main__":
    run_all()
