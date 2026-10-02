# E-BikeCockpit 0.4.3 — versionCode 11

- Pendant l’exploration du profil d’altitude, les champs habituels affichent les valeurs du point sélectionné : vitesse, altitude, pente, distance depuis le départ, dénivelé positif cumulé et temps en mouvement cumulé jusqu’à ce point. Fonctionne en portrait et en paysage.
- L’écran indique « POINT DU PARCOURS » ; un bouton « RETOUR AU DIRECT » libère la sélection et rétablit les mesures actuelles. Toucher le profil rétablit aussi le direct et change sa fenêtre, comme auparavant. Relâcher le glissement conserve la sélection et le centre d’orbite.
- Le cadran affiche immédiatement la vitesse du point pendant le glissement, sans interpolation animée entre les valeurs historiques. Le glitch est conservé si cette vitesse était recalculée. Les petits cadrans roue/moteur en direct sont masqués pendant la consultation historique pour éviter de mélanger les instants.
- Le D+, le chronomètre et l’origine de la vitesse sont maintenant mémorisés à l’acquisition de chaque point et ajoutés aux nouveaux journaux CSV. Le temps correspond au chronomètre en mouvement avec son seuil configuré, pas au temps total écoulé avec les pauses. Les points sans données historiques correspondantes affichent un tiret ; aucune valeur actuelle n’est utilisée à leur place.
- L’acquisition GPS, le calcul du trajet et le chargement des cartes continuent pendant la consultation. La sélection ne modifie pas les données du trajet.

ApplicationId et signatures Android inchangés ; aucune donnée personnelle de trajet incluse dans le commit.

Validation : 82 tests unitaires réussis, builds debug/release et lint vital release réussis. Version debug installée et démarrée par USB sur le OnePlus 8T, version 0.4.3 / code 11 vérifiée. Pas de tests UI prolongés.
