# Reprise du projet AuroraStore

Sources et contenus du projet, sans les caches, compilations et anciennes archives.
Trois états au maximum : branches reprise-actuelle, reprise-precedente-1 et reprise-precedente-2 (si présentes).
Les fichiers conservés sont vérifiés par SHA-256. Cela ne certifie pas une compilation réussie sur toute machine.
Lire aussi le README original. Les SDK et dépendances téléchargeables ne sont pas des sauvegardes de sources.

## Clés de signature

Elles sont conservées chiffrées, jamais en clair dans Git. Le fichier-clé personnel doit être gardé HORS de GitHub, sur un autre support.
Sur cet Ubuntu : /home/server/ProjectRecovery-Key.txt. Sans ce fichier, les clés archivées ne peuvent pas être récupérées.
Restaurer : `python3 .reprise/restore-signing.py /chemin/ProjectRecovery-Key.txt`.
Ne jamais ajouter à Git les clés déchiffrées.

## Android

Ouvrir ce dossier dans Android Studio. Utiliser le SDK installé : /home/server/Android/Sdk.
La copie de local.properties est volontairement absente ; Android Studio la recrée.
Pour un test : `ANDROID_HOME=/home/server/Android/Sdk bash ./gradlew :app:assembleDebug`.
