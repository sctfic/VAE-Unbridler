> Mise à jour 0.4.0 : les objets OSM sont désormais acquis par tuiles de niveau 2 autour du GPS (zone de 1,8 km de côté plus marge), indépendamment de l’étendue du trajet. Le préchargement Options → Hors ligne conserve un secteur de 90°, rayon 2–50 km, orienté dans huit directions. Le relief de niveau 1 et les calques activés sont mis en cache, avec état complet/partiel et reprise. Les fichiers protégés hors ligne échappent à la limite LRU habituelle ; ils peuvent donc occuper davantage d’espace. Voir [les détails et limites](RELEASE_0.4.0.md).

# Chargement 3D et diagnostic — 0.3.3

Depuis 0.3.3, les altitudes sont acquises par petites tuiles fixes. La tuile GPS
est prioritaire, puis les données voisines complètent progressivement la vue.
Le ViewModel conserve l'acquisition à la rotation ; changer le détail du rendu
ne modifie plus la résolution demandée au serveur. Une extension réelle de la
zone peut toujours demander de nouvelles tuiles. Les points ci-dessous détaillent
les catégories du diagnostic ; le protocole effectif est décrit dans
[TERRAIN_STREAMING_PLAN.md](TERRAIN_STREAMING_PLAN.md).

Affichage des deux diagnostics (GPS et chargement 3D) : appui long sur le 3D,
puis case **Diagnostic GPS et chargement 3D**. Choix mémorisé, désactivé par défaut.
L'enregistrement GPS n'est pas affecté. L'évaluation de la prochaine stratégie
de tuiles progressives se trouve dans [TERRAIN_STREAMING_PLAN.md](TERRAIN_STREAMING_PLAN.md).

1. **GPS** : première position disponible. Elle déclenche immédiatement la
   recherche du relief (plus d'attente pouvant atteindre 5 s au premier fix).
2. **ALT** : recherche locale d'une grille IGN couvrant la zone, vérification de
   résolution. Si suffisante, aucun appel IGN. Sinon affichage immédiat d'un
   aperçu couvrant en cache, puis amélioration par lots IGN, avec attente du quota.
   En absence de couverture/données : tuiles MapZen, cache avant réseau.
3. **OSM** : RAM, puis cache JSON, puis Overpass seulement si la zone ou le niveau
   de détail manque. Le terrain peut être affiché avant ces objets.
4. **OBJ** : projection des routes, chemins, eaux et bâtiments sur le relief.
   Les tableaux de coordonnées projetées sont désormais cachés en RAM et sur disque.
5. **3D** : interpolation, triangles et courbes de niveau. Le résultat statique
   est désormais caché sur disque, par contenu du relief et niveau de détail.
   La trace GPS et ses couleurs restent dynamiques ; elles ne sont pas dans ce cache.
6. **GPU** : préparation des buffers et soumission des commandes de dessin.
   Durée CPU mesurée, pas un chronométrage matériel du GPU ou de l'affichage écran.

Les lignes affichent la dernière opération de chaque fil de chargement et sa
durée totale. Certaines étapes se chevauchent : ne pas additionner les durées.
Les durées 3D/GPU sont celles de la dernière reconstruction, y compris après
changement de couche ou arrivée d'un point GPS. Aucun délai de 10 s n'est imposé.

## Politique de cache

- Données IGN et MapZen nouvellement enregistrées dans le stockage privé
  persistant de l'app, avec réutilisation des anciens caches Android existants.
- Budgets des nouvelles données : 64 Mio IGN, 64 Mio MapZen, 64 Mio OSM.
- Géométrie dérivée : `files/geometry-v1`, 128 Mio disque, jusqu'à 4 entrées en RAM
  (cible 32 Mio, une entrée peut dépasser cette cible ; plafond disque/entrée 48 Mio).
- Clé SHA-256 : version de calcul, zone, altitudes, détail, et contenu des objets
  pour leur projection. Changer de zone/résolution ne réutilise pas une géométrie
  incompatible. Si le format est invalide/tronqué, recalcul sans planter.
- Écriture via fichier temporaire puis renommage. Éviction des plus anciens
  fichiers quand le budget est atteint. Pas de téléchargement à chaque ouverture
  si la zone et la résolution nécessaires sont déjà présentes.
- L'ancien cache n'est pas effacé à la migration. Effacer les données de l'app
  ou la désinstaller efface ses caches persistants.

Un premier chargement à froid peut toujours prendre plusieurs secondes : lots
IGN, réseau mobile, délai serveur Overpass. Le panneau distingue ces attentes des
calculs locaux. Les requêtes réseau transmettent la zone géographique demandée.
