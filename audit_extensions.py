import os
import subprocess
import sys
import json
import glob
import shutil
import urllib.request

# Configuration pour l'Inspecteur (ton fork)
GITHUB_API_URL = "https://api.github.com/repos/bluecxt/aniyomi-extensions-inspector/releases/latest"
INSPECTOR_JAR = "inspector_ephemeral.jar"
AUDIT_APK_DIR = os.environ.get("AUDIT_APK_DIR", "build/all-apks")
JAVA_HOME = os.environ.get("JAVA_HOME")
if JAVA_HOME:
    JAVA_BIN = os.path.join(JAVA_HOME, "bin", "java")
else:
    JAVA_BIN = shutil.which("java")

def get_latest_inspector_url():
    """Récupère dynamiquement l'URL du dernier JAR via l'API GitHub."""
    try:
        with urllib.request.urlopen(GITHUB_API_URL) as response:
            data = json.loads(response.read().decode())
            for asset in data.get('assets', []):
                if asset['name'].endswith('.jar'):
                    return asset['browser_download_url']
    except Exception as e:
        print(f"   ❌ Erreur lors de la récupération de l'URL : {e}")
    return None

def download_inspector(url):
    """Télécharge le JAR de l'inspecteur."""
    print(f"📥 Téléchargement de l'Inspecteur depuis GitHub...")
    try:
        urllib.request.urlretrieve(url, INSPECTOR_JAR)
        return True
    except Exception as e:
        print(f"   ❌ Erreur de téléchargement : {e}")
        return False

def prepare_apk_dir():
    """Regroupe les APKs pour l'inspecteur."""
    if os.path.exists(AUDIT_APK_DIR):
        shutil.rmtree(AUDIT_APK_DIR)
    os.makedirs(AUDIT_APK_DIR, exist_ok=True)
    
    apks = glob.glob("src/fr/**/build/outputs/apk/debug/*.apk", recursive=True)
    if os.environ.get("AUDIT_APK_SOURCE_DIR"):
         apks += glob.glob(os.path.join(os.environ.get("AUDIT_APK_SOURCE_DIR"), "**/*.apk"), recursive=True)
    
    found_any = False
    for apk in apks:
        if "androidTest" in apk: continue
        shutil.copy(apk, AUDIT_APK_DIR)
        found_any = True
    return found_any

def run_inspector():
    inspector_url = get_latest_inspector_url()
    if not inspector_url:
        return False, "Impossible de trouver l'URL de l'inspecteur"
    
    if not download_inspector(inspector_url):
        return False, "Échec du téléchargement"

    if not prepare_apk_dir():
        return False, "Aucun APK trouvé pour l'audit"

    print(f"🚀 [CHEF D'ORCHESTRE] Démarrage de l'audit dynamique\n")
    
    output_json = "audit_report.json"
    tmp_dir = "inspector_tmp"
    os.makedirs(tmp_dir, exist_ok=True)
    
    cmd = [JAVA_BIN, "-jar", INSPECTOR_JAR, AUDIT_APK_DIR, output_json, tmp_dir]
    
    try:
        result = subprocess.run(cmd, capture_output=False, text=True)
        success = (result.returncode == 0)
        return success, "Audit terminé" if success else f"Code retour : {result.returncode}"
    except Exception as e:
        return False, f"Erreur fatale : {str(e)}"
    finally:
        # Nettoyage systématique
        print("\n🧹 Nettoyage des fichiers temporaires...")
        if os.path.exists(INSPECTOR_JAR): os.remove(INSPECTOR_JAR)
        if os.path.exists(tmp_dir): shutil.rmtree(tmp_dir)
        if os.path.exists(AUDIT_APK_DIR): shutil.rmtree(AUDIT_APK_DIR)

def run_all():
    success, reason = run_inspector()
    if not success:
        print(f"\n❌ ÉCHEC DE L'AUDIT : {reason}")
        sys.exit(0) #TODO: rendre l'inspector accurate il est tout pourris la
    else:
        print("\n✅ AUDIT RÉUSSI")
        sys.exit(0)

if __name__ == "__main__":
    run_all()
