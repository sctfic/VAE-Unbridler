# Chargement 3D et caches

Le panneau de diagnostic et les mesures de durée ont été supprimés en 0.4.28.
Le chargement progressif et les cartes hors ligne restent disponibles.
Voir [TERRAIN_STREAMING_PLAN.md](TERRAIN_STREAMING_PLAN.md) pour la stratégie
et [RELEASE_0.4.0.md](RELEASE_0.4.0.md) pour le préchargement par secteur.

La tuile de relief proche du GPS est prioritaire, puis les données voisines
complètent progressivement la scène. Le ViewModel conserve l'acquisition à la
rotation. Changer le détail du rendu ne change pas la densité demandée au serveur.
Les objets OSM et le cadastre sont chargés indépendamment du relief.

Les options, accessibles par un appui long sur la scène, choisissent les couches
et le secteur hors ligne. Les progressions de téléchargement et la taille du
cache restent visibles ; ces valeurs ne sont pas enregistrées dans des journaux.

## Politique de cache

- Données de carte et de relief dans le stockage privé persistant, sans migration
  des anciens formats de cache.
- Budgets ordinaires : 64 Mio IGN, 64 Mio Mapzen, 64 Mio OSM.
- Géométrie dérivée : 128 Mio disque, quatre entrées RAM au maximum, cible 32 Mio.
  Les fichiers de plus de 48 Mio ne sont pas enregistrés dans ce cache.
- Les clés SHA-256 incluent la zone, le relief, le détail et les objets projetés.
- Écriture temporaire puis renommage, éviction LRU. Les fichiers protégés hors
  ligne échappent au quota ordinaire et peuvent donc occuper davantage d'espace.

Une première acquisition peut prendre plusieurs secondes. Les fournisseurs
reçoivent la zone géographique demandée. Les points du parcours restent en
mémoire ; ils ne font pas partie du cache cartographique.
