# E-BikeCockpit 0.4.8 — versionCode 16

- Libellé REPOS réduit ; durée conservée en grand et mise en évidence en magenta.
- Alternance exclusive des compteurs : vitesse valide au-dessus du seuil = mouvement ; sinon repos, même sans signal. Repos remis à zéro et masqué à la reprise uniquement.
- Crochets magenta au début et à la fin des pauses sur le profil et le parcours 3D. Une pause en cours affiche seulement son début.
- Retirer l’application des récents arrête GPS, BLE, verrou CPU et notification. Surveillance passive de présence Android conservée ; pas de relance avant une nouvelle disparition/apparition de l’ESP ou une ouverture manuelle.
- Conservation du seul APK signé le plus récent dans releases ; nettoyage des anciens APK des publications GitHub après publication réussie.

Validation : 86 tests unitaires réussis ; APK debug/release et lint vital release compilés. Debug 0.4.8 / code 16 installé sur le OnePlus 8T. Action Arrêter vérifiée : service absent et verrou CPU libéré, puis cockpit rouvert. Le geste de retrait des récents et le cycle de présence réel de l’ESP restent à valider sur le terrain.
