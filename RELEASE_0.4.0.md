# E-BikeCockpit 0.4.0 — versionCode 8

- Vitesse GPS de secours : régression pondérée est/nord en fonction du temps sur six secondes, avec au moins trois positions et deux secondes. Affichage ≈ légèrement brouillé. Le GPS natif reste prioritaire ; la roue reste le repli si aucune vitesse GPS utilisable. Aucun chiffre inventé sans positions récentes.
- Pente : régression altitude/distance sur 11 points interpolés des 20 derniers mètres (12 m minimum). Lissage spatial de l’altitude sur une longueur de 8 m, interruption aux lacunes GPS/altitude, exclusion des incertitudes verticales déclarées supérieures à 15 m. Le résultat reste une estimation GPS, sans correction barométrique.
- Chronomètre sous la vitesse à gauche : uniquement au-dessus de 4 km/h, seuil réglable de 0 à 20 km/h dans les réglages roue. Horloge monotone, pas de comptage des lacunes de plus de quatre secondes. Remise à zéro avec le trajet.
- Dénivelé positif en bas à droite de la scène : cumul des altitudes lissées, hystérésis de 3 m pour limiter le bruit. Les montées inférieures à 3 m et les lacunes ne sont pas additionnées. Remise à zéro avec le trajet.
- Objets OSM chargés par tuiles autour de la position courante (zone locale de 1,8 km de côté, plus marge des tuiles) même dans la vue du trajet complet. Relief et cadastre continuent leur chargement progressif à proximité. Les objets éloignés du vélo ne sont pas tous affichés en vue complète.
- Appui long sur la 3D → Options → Hors ligne : rayon 2–50 km, huit orientations sur une boussole, secteur de 90°. Relief systématique, groupe OSM si un de ses calques est actif, cadastre si activé. Relief hors ligne : tuiles IGN de niveau 1, pas au sol au plus 32 m ; le détail fin déjà présent reste utilisé. Les calques OSM partagent leur téléchargement.
- Progression, interruption et reprise par relancement. Les téléchargements conservés sont protégés de l’éviction du cache, y compris le cache déjà présent au lancement. Le stockage protégé peut donc dépasser 64 Mio par source. Arrêt si l’espace libre passe sous 128 Mio. Garder l’application ouverte pendant le préchargement ; pas de tâche garantie après arrêt du processus. Un statut partiel signale les données absentes, les erreurs et le cadastre tronqué ; il ne garantit pas un itinéraire entièrement couvert. Les caches peuvent être effacés via les données de l’application Android.
- Glisser sur le profil choisit un point du trajet comme centre des manipulations 3D. La ligne blanche indique la sélection ; toucher le profil change sa fenêtre et rétablit le centre automatique.
- Caméra automatique à 38° au-dessus de l’horizon en mouvement, modulée par la pente. Contraste et épaisseur augmentent progressivement entre 1 000 et 10 000 lux si le téléphone possède un capteur de lumière ; largeur limitée aux capacités OpenGL du téléphone.

L’identifiant Android et la clé release existante sont conservés. Aucun journal de trajet, coordonnée personnelle ni secret n’est inclus dans le commit ou l’APK.

## Validation et APK

71 tests unitaires réussis, aucune erreur ; compilation release et debug et lint vital release réussis. Aucun parcours de tests UI automatisé.

- `releases/E-BikeCockpit-0.4.0.apk` : release signée avec la clé release existante.
- `releases/E-BikeCockpit-0.4.0-debug.apk` : variante de développement, installée par mise à jour USB sur le OnePlus 8T. La 0.3.4 présente sur ce téléphone était une version debug ; Android refuse de la remplacer par une signature release différente. L’installation debug conserve les données.
- Empreintes dans `releases/SHA256SUMS.txt`.
