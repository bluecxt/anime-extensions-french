#!/usr/bin/env python3
import os
import re

def bump_extension_versions():
    # Chemin du dossier src (relatif à la racine du projet)
    src_dir = os.path.join(os.getcwd(), "src")
    
    if not os.path.exists(src_dir):
        print(f"[-] Erreur : Le dossier {src_dir} est introuvable.")
        print("[!] Assure-toi de lancer le script depuis la racine du dépôt git.")
        return

    # Regex pour cibler précisément la ligne extVersionCode et capturer sa valeur numérique
    version_code_regex = re.compile(r"(\s*extVersionCode\s*=\s*)(\d+)")
    
    modified_files_count = 0
    print("[+] Recherche des fichiers build.gradle dans src/...")
    print("-" * 60)

    # Parcours récursif de src/all et src/fr
    for root, dirs, files in os.walk(src_dir):
        if "build.gradle" in files:
            file_path = os.path.join(root, "build.gradle")
            
            # Lecture du fichier
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()

            # Vérification si le fichier contient bien la variable extVersionCode
            match = version_code_regex.search(content)
            if match:
                prefix = match.group(1)       # Contient par exemple "    extVersionCode = "
                current_version = int(match.group(2))
                new_version = current_version + 1

                # Remplacement de la version dans le contenu
                new_content = version_code_regex.sub(f"{prefix}{new_version}", content)

                # Écriture des modifications
                with open(file_path, "w", encoding="utf-8") as f:
                    f.write(new_content)

                # Extraction du nom relatif du module pour l'affichage (ex: fr/animesama)
                relative_module = os.path.relpath(root, src_dir)
                print(f"[UPDATED] {relative_module:<25} : v{current_version} -> v{new_version}")
                modified_files_count += 1
            else:
                # Si un build.gradle n'a pas de extVersionCode (ex: un fichier de config globale)
                relative_module = os.path.relpath(file_path, os.getcwd())
                print(f"[IGNORED] {relative_module} (Pas de extVersionCode trouvé)")

    print("-" * 60)
    print(f"[+] Opération terminée. {modified_files_count} extensions ont été mises à jour.")

if __name__ == "__main__":
    bump_extension_versions()