# E-BikeCockpit 0.4.6 — versionCode 14

- Chronomètre rapproché du libellé : suppression du padding typographique et interligne compact.
- Affichage mm:ss tant que les heures sont nulles, puis hh:mm:ss.
- Sous la valeur, petite ligne « REPOS mm:ss » uniquement pendant une phase mesurée à une vitesse inférieure ou égale au seuil. Le compteur repart de zéro à chaque mouvement valide au-dessus du seuil.
- Les pertes de mesure et interruptions supérieures à quatre secondes ne sont pas comptées comme du repos. Sans vitesse exploitable, la ligne est masquée et le compteur est suspendu.
- Le repos est également enregistré dans les points du trajet et les nouveaux journaux CSV, pour afficher sa valeur historique lors du glissement sur le profil. Le reset trajet remet tous les compteurs à zéro.

Validation : 87 tests unitaires réussis, APK debug/release et lint vital release compilés avec succès. APK debug installé et démarré par USB sur le OnePlus 8T, version 0.4.6 / code 14 vérifiée. Aucun test UI prolongé.
