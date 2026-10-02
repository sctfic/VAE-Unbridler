# E-BikeCockpit — Lopez Alban

Avant chaque commit, incrémenter `versionCode` et `versionName` dans
`android-app/app/build.gradle.kts` par rapport au HEAD précédent. Respecter la
version explicitement demandée par l'utilisateur ; ne pas la réincrémenter si
elle a déjà été augmentée dans les modifications du commit en préparation.

Conserver l'applicationId pour les mises à jour Android et la clé de signature
release existante. Ne jamais committer les clés privées, mots de passe,
`signing.properties`, positions GPS personnelles ou journaux de trajet.

Après compilation, utiliser `tools/package-apk.ps1` : ne conserver dans `releases/` que le dernier APK signé. Le debug reste dans les sorties de build pour installation USB. La publication via `tools/publish-release.ps1` retire les anciens APK des releases GitHub après vérification du nouveau téléchargement, sans supprimer les tags ni les notes.
