#!/usr/bin/env python3
"""Restore encrypted project signing files without overwriting different files."""
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile

os.umask(0o077)
root = Path(__file__).resolve().parent.parent
key = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else Path('/home/server/ProjectRecovery-Key.txt')
payload = root / '.reprise/signing.tar.gpg'
if not payload.exists():
    raise SystemExit('Ce projet ne contient pas de clés chiffrées à restaurer.')
if not key.is_file():
    raise SystemExit('Indiquer le fichier-clé personnel : python3 .reprise/restore-signing.py /chemin/cle.txt')
result = subprocess.run(['gpg', '--batch', '--pinentry-mode', 'loopback', '--passphrase-file', str(key),
                         '--decrypt', str(payload)], capture_output=True)
if result.returncode:
    raise SystemExit('Déchiffrement impossible : vérifier le fichier-clé.')
with tarfile.open(fileobj=io.BytesIO(result.stdout), mode='r:gz') as archive:
    validated = []
    for member in archive.getmembers():
        name = PurePosixPath(member.name)
        target = root / member.name
        if not member.isfile() or name.is_absolute() or '..' in name.parts or not target.resolve().is_relative_to(root):
            raise SystemExit('Chemin non sûr dans la sauvegarde : extraction refusée.')
        content = archive.extractfile(member).read()
        if target.exists() and hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(content).digest():
            raise SystemExit('Fichier existant différent, conservé : ' + member.name)
        validated.append((target, content))
    for target, content in validated:
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            with target.open('xb') as stream:
                stream.write(content)
        target.chmod(0o600)
print(f'{len(validated)} fichier(s) de signature restauré(s). Ne pas les ajouter à Git.')
