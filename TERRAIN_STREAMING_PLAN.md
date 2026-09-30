# Chargement progressif — évaluation et réalisation 0.3.3

## Réalisation

La version 0.3.3 introduit `TerrainViewModel`, `ElevationTiles` et
`SharedTileRequests`. Les téléchargements ne dépendent plus du cycle de vie de
la vue ; une rotation ne les annule pas. Les requêtes simultanées pour une même
tuile sont partagées et chaque tuile terminée est sauvegardée atomiquement.

- Quadrillage Web Mercator fixe : côté de 256 mètres **projetés**, 33 × 33
  échantillons. Le pas réel au sol est inférieur ou égal à 8 m (environ 5,4 m à
  48° de latitude). Ce ne sont pas des dalles IGN natives de 1 m.
- Ordre : toute donnée locale disponible, tuile fine du GPS, au plus 4 tuiles
  lointaines moins fines, puis voisines fines (9 tuiles fines au total).
- Les grilles antérieures sont utilisées comme aperçu, ou importées localement
  lorsqu'elles ont une résolution suffisante. Aucun ancien cache n'est effacé.
- Jusqu'à 256 tuiles fines déjà acquises dans la zone sont reprises pour les vues
  plus larges sans téléchargement. La grille de rendu (257 × 257 maximum ici)
  est simplifiée localement selon 500 m / 2 km / tout.
- Bords de tuiles identiques ; transition sur deux cellules vers le fond moins
  fin aux bords dépourvus de voisin fin. Les trous sans altitude ne deviennent
  pas des altitudes zéro. MapZen reste le repli en absence de données IGN.
- Cache numérique : 64 Mio disque, 128 tuiles RAM ; cache géométrique existant
  conservé. Une réponse sans altitude a un cache négatif de 24 h, un échec réseau
  entraîne une temporisation. Les requêtes en arrière-plan sont bornées à la
  zone demandée, et non une exploration sans limite.
- OSM conserve son cache par zone, mais son dépôt et sa requête sont maintenant
  conservés par le ViewModel. Il ne bloque pas le relief et n'est plus relancé
  à chaque nouvelle tuile d'altitude. Le pavage OSM fin reste une amélioration
  distincte : une extension réelle de zone peut encore nécessiter une requête.

Les paragraphes suivants conservent l'évaluation initiale. Les chiffres de taille
ci-dessus décrivent les paramètres effectivement retenus.

## Constats dans le code actuel

- `TerrainScene` recrée ses dépôts et sa grille en mémoire après recréation de
  l'activité ou changement de branche portrait/paysage. Les chargements sont
  liés à la composition et peuvent être annulés à la rotation/changement de zoom.
- La recherche IGN utilise `contains` avec une marge de 0,96. Une grille dont
  le demi-côté vaut 742,5 m ne couvre ainsi que 712,8 m utilisables : elle est
  rejetée pour une nouvelle demande de 742,5 m, même au même centre.
- La recherche exacte de secours inclut les coordonnées GPS en double dans la
  clé : une petite dérive GPS rend cette clé différente.
- Le cache stocke une grille recentrée complète. Il ne sait pas assembler des
  morceaux déjà acquis pour compléter seulement une bordure manquante.
- Les modes choisissent 129², 97² et 65² échantillons. Passer vers un mode plus
  détaillé peut légitimement demander des données absentes, mais revenir à un
  détail inférieur ne devrait pas redéclencher un téléchargement déjà couvert.
- Les lots IGN ne sont persistés qu'après la fin de la grille complète. Une
  annulation intermédiaire peut perdre les lots reçus, puis les télécharger à nouveau.
- Orientation non présente dans la clé disque : le phénomène observé n'est pas
  six caches explicitement séparés. Ces causes sont établies par lecture du code,
  pas par une mesure réseau des six combinaisons sur le téléphone.

## Approche recommandée

Oui au chargement progressif, mais avec un **quadrillage géographique fixe**,
indépendant du GPS exact, de l'orientation et de l'échelle d'affichage.

1. Un dépôt conservé par ViewModel partage les requêtes en cours. Une rotation
   recrée le rendu, pas les téléchargements. Les requêtes sont dédupliquées par tuile.
2. Charger immédiatement toute donnée locale couvrante, même moins détaillée,
   puis la petite tuile contenant le GPS. Dimension initiale à mesurer : 256 m,
   grille 33 × 33, pas de 8 m. 1 089 points contre 16 641 pour la grille fine
   actuelle (environ 15 fois moins de points, pas une promesse de vitesse ×15).
3. Persister chaque tuile achevée immédiatement. Précharger ensuite ses voisines,
   en priorité dans la direction du mouvement et sur le parcours affiché.
4. Simplifier localement les données fines disponibles pour 2 km et « tout ».
   Garder une couronne lointaine moins fine : télécharger toute une grande région
   à haute résolution coûterait trop de données et retarderait l'affichage.
5. Ne télécharger que les tuiles absentes ou réellement insuffisantes. Les clés
   incluent fournisseur, identifiant géographique, pas d'échantillonnage et version
   de format ; jamais orientation ou coordonnées GPS instantanées.
6. Cacher les maillages de chaque niveau de détail. Raccorder les bords des tuiles
   (échantillons communs et transitions de résolution), préserver les trous sans
   données et une référence altimétrique commune.
7. Même logique pour OSM, avec son propre cache et budget. Son téléchargement
   ne bloque pas l'affichage du relief et ses couches masquées ne sont pas prioritaires.

Le nom du produit IGN « RGE ALTI 1 m » ne signifie pas que l'application reçoit
actuellement une grille à pas de 1 m. À la taille minimale actuelle de 1 485 m,
le pas du mode 500 m est environ 11,6 m. Un pas de 8 m reste une proposition
à mesurer, pas une reproduction des données IGN natives à 1 m.

## Préconditions et validation

- Séparer la marge de préchargement de la couverture réelle du cache : ne pas
  rejeter une grille couvrante pour une marge destinée à anticiper le déplacement.
- Sans effacer les caches existants, les réutiliser en aperçu pendant la migration.
- Mesurer premier relief visible, relief complet, octets téléchargés et cache hits.
- Sur une zone déjà couverte à la résolution requise : zéro appel terrain pour
  portrait/paysage × 500 m/2 km/tout, y compris avec faible dérive GPS.
- Tester frontière de tuile, mouvement, hors ligne, annulation, cache corrompu,
  mémoire, raccords visuels, trajet long et disponibilité partielle IGN.
- Sur cache froid : l'affichage de la tuile centrale ne doit pas attendre les
  voisines ou OSM. Ne pas fixer un objectif temporel garanti sans mesure réseau.

## Livré dans cette étape

Une seule case « Diagnostic GPS et chargement 3D » dans les options par appui
long. Elle masque/affiche les deux panneaux, est désactivée par défaut et mémorisée.
Elle ne coupe ni le GPS, ni l'enregistrement, ni les calculs de diagnostic.
La refonte terrain est implémentée en 0.3.3 selon les paramètres détaillés en tête.
