# Cockpit holographique

L'interface Android suit l'orientation du téléphone, en portrait ou en paysage. La barre d'état
reste visible ; la navigation est masquée, accessible par balayage du bord et s'affiche alors
temporairement par-dessus l'application. En paysage, les 80 % supérieurs de la surface utile contiennent le cadran à gauche
(40 % de largeur) et la scène 3D à droite (60 %), séparés par une ligne.
Le profil sélectionnable occupe les 20 % inférieurs sur toute la largeur. La scène 3D
de droite se poursuit derrière ce profil, sans séparation horizontale.
Altitude et pente sont en haut du relief, la rose des vents en haut à droite,
la distance en bas à gauche. Standard est bleu, Turbo rouge.
Le nom du modèle altimétrique est en bas à droite de la scène. Dans les deux orientations,
le profil s'étend derrière les mentions de bas de page : distance de fenêtre, « ICI »,
« RELIEF · IGN / MAPZEN » et le lien « Auteur Lopez Alban 2026 · GitHub ».
Le titre « Profil 500 M » et les mentions d'aide « toucher pour changer »,
« parcours complet » et « vue 360° » ne sont pas affichées.
En portrait, l'agencement précédent est conservé : en-tête, vitesses en haut (38 %),
scène 3D en dessous (62 %), altitude/pente en haut du relief, rose des vents en haut à droite,
distance en bas à gauche au-dessus du profil de 104 dp, abaissée de 24 dp.
Le mode 3D figure après « DISTANCE » ; la rose des vents est décalée à droite et
partage le même estompage supérieur que les mesures. La pente est décalée de 12 dp à gauche.

## Routes et cours d'eau

Les géométries © contributeurs OpenStreetMap (ODbL) sont téléchargées via
[Overpass](https://dev.overpass-api.de/overpass-doc/en/full_data/bbox.html), séparément du relief IGN.
Les routes sont gris clair et les cours d'eau bleus. Les segments sont découpés aux
limites du terrain puis subdivisés et projetés sur ses altitudes. Les trous de données
ne sont pas reliés. Les tunnels sont omis ; les ponts sont projetés au sol.
La vue complète omet les chemins et petits cours d'eau ; les emprises larges ne
demandent que les axes principaux. Le téléchargement est limité à 12 km de demi-largeur
plus 20 % de marge, 8 Mio par réponse, 150 000 points source et 200 000 sommets de rendu.
Sur un parcours plus vaste, les couches routières/hydrographiques ne couvrent donc que
la zone centrale. Cache persistant dédié de 64 Mio, lecture prioritaire sans expiration,
repli silencieux en cas d'indisponibilité, nouvelle tentative après 2 minutes. La zone
consultée est transmise au serveur Overpass ; aucun historique GPS n'est envoyé.
Licence et attribution : [OpenStreetMap](https://www.openstreetmap.org/copyright).
La vitesse principale privilégie le GPS valide puis la roue connectée. Les diagnostics
GPS et le journal existants sont conservés.

Le cadran et la grande vitesse sont centrés. Les vitesses roue (en haut à gauche)
et moteur (en haut à droite) sont symétriques. La validation de la trace est
indépendante de l’incertitude de vitesse : une mesure de vitesse moins précise
ne coupe plus une série de positions cohérentes. Le lissage court de la vitesse
combine médiane et filtre exponentiel, avec remise à zéro sur arrêt confirmé.
La vitesse principale est arrondie à l'unité et affichée en gras ; les petites vitesses
roue et moteur conservent une décimale.

## Géométrie et affichage

Le relief calculé et ses buffers OpenGL sont réutilisés entre les mises à jour GPS.
Un échec réseau ne remplace pas un terrain valide par une grille vide. Le tampon de
profondeur 24 bits, le plan proche relatif à la distance et le décalage des polygones
stabilisent la superposition des courbes de niveau pendant la rotation.

- Repère local métrique Est/Nord ; cap horaire depuis le nord. L'est est à droite quand
  le vélo va vers le nord. Le cap de déplacement utilise au moins 10 m de déplacement.
- Caméra perspective : inclinaison automatique `50° − 1,5 × atan(pente / 100)` bornée à
  30–70°, transition progressive. Cela change le point de vue, sans déformer le relief réel.
  Interpolation du cap par le chemin angulaire le plus court.
- Cadrage calculé sur la trace avec marges (80 % de la demi-largeur, 64 % de la demi-hauteur).
  Le zoom s'élargit immédiatement pour ne pas couper la trace et se resserre progressivement.
- Échelle identique Est/Nord, sans étirement. Le terrain remplit le fond ; une ligne droite
  ne peut remplir les deux dimensions sans déformation. Le viewport découpe tout débordement.
- Parcours complet de la session sans coupure de longueur ni plafond de points, échantillonnage
  de 2 m et simplification des portions rectilignes limitée à 25 m. Virages et interruptions GPS
  préservés. La mémoire croît avec la complexité du trajet ; la projection locale est destinée
  aux parcours régionaux, pas à un tour du globe. Les variations de vitesse/pente sont
  prises en compte pour ne pas supprimer les changements de couleur lors de la simplification.
- OpenGL ES 2 : relief triangulé éclairé, filaire, courbes de niveau tous les 10 m,
  halo de trace et balise de position. Les largeurs de ligne sont bornées aux capacités GPU.
- Le détail IGN varie avec la fenêtre 3D : 500 m = source 129 × 129 / rendu 257 × 257 / courbes 5 m ; 2 km = 97 × 97 / 193 × 193 / 10 m ; tout = 65 × 65 / 129 × 129 / 20 m. Une grille plus fine en cache peut servir à un mode moins détaillé, sans téléchargement.
  Cette interpolation, bornée par les échantillons voisins, adoucit les courbes de niveau sans
  inventer une précision de mesure. Le dénivelé du relief et de la trace est amplifié ×1,8
  uniquement dans la scène 3D ; altitude, pente, profil et distance conservent leurs valeurs.
- Une rose des vents projetée comme un disque horizontal est affichée en haut à droite. Elle
  suit le cap et l'inclinaison manuels ou automatiques et indique le cadrage actif.
- Toucher simple dans la 3D : **tout → 500 m → 2 km → tout**. Le premier point conservé est
  marqué comme début de segment afin de ne pas tracer une liaison artificielle. Double toucher
  reste réservé au Reset ; un glissement ou un pincement ne change pas le cadrage.
- Rendu demandé au maximum toutes les 33 ms (cible 30 images/s) ; arrêt à la mise en arrière-plan.
  Maillage et terrain préparés hors du thread UI. Cette cible n'est pas une mesure de performance.

## Gestes et remise à zéro

- Glisser horizontalement sur la 3D fait tourner la caméra autour du trajet (360° par largeur
  d'écran), verticalement règle l'inclinaison entre 20 et 80°. Pincer à deux doigts zoome
  entre ×0,5 et ×4. En zoom manuel, seule une partie du parcours peut être visible.
  Les réglages manuels sont conservés pendant le contact et trois secondes après le dernier
  relâchement, puis la caméra revient progressivement au cadrage automatique et à la pente.
- Après cinq secondes sans progression d'au moins 3 m de distance GPS validée, la caméra
  tourne lentement à 4°/s. Une progression significative réactive le suivi du cap, interpolé.
- Double toucher sur la 3D : confirmation **Reset** / **Annuler**. Reset remet à zéro trace,
  distance, profil et filtre GPS ; le suivi continue et un nouveau journal de trajet est ouvert.
  Aucun ancien fichier n'est supprimé. Retour Android ou Annuler ne modifie rien.
- Le double toucher Standard/Turbo est désormais réservé à la zone des vitesses.

## Pente et profils

La pente affichée est `100 × (altitude finale − altitude de référence) / distance parcourue`.
La référence est interpolée sur le profil 20 m avant son extrémité ; au début d'un segment,
12 m au minimum sont nécessaires. Les interruptions GPS/altitude ne sont jamais reliées.
L'ancien calcul à partir de la position courante et d'un point à vol d'oiseau, plafonné
à ±25 %, a été supprimé. Le filtrage d'altitude ne converge plus au fil du temps à l'arrêt :
altitude de trajet, profil et pente restent figés sans progression validée. « — » signifie
qu'il manque des données. Cela demeure une estimation GPS, pas une mesure d'inclinomètre.

Un toucher du profil fait défiler **500 m → complet → 2 km → 500 m**. L'historique complet
est conservé pendant la session et les interruptions sont visibles ; la pente calculée
ne dépend pas du niveau de zoom du graphique.

La courbe du profil reprend la palette bleu → vert → jaune → rouge en fonction de l'altitude
relative dans la fenêtre visible. Son extrémité porte une balise pulsante synchronisée sur
2,2 s avec celle de la scène 3D. Les points minimum et maximum sont marqués et libellés
directement sur la courbe ; les textes sont contraints aux limites du graphique.

## Couleurs du parcours

Un toucher dans les vitesses sélectionne la vitesse GPS enregistrée à chaque point ; toucher
Altitude ou Pente sélectionne cette mesure. La légende et sa barre restent masquées au
démarrage et apparaissent pendant cinq secondes après chaque sélection, y compris un
nouveau toucher sur la mesure déjà active. Les mises à jour GPS ne relancent pas ce délai.
La coloration choisie reste active après disparition de la légende.
Palette bleu → vert → jaune → rouge, interpolée le long des segments : vitesse 0–50 km/h,
pente −15 à +15 %, altitude min/max du parcours (au moins 1 m d'écart). Les valeurs hors
échelle saturent en couleur sans modifier la mesure ; les valeurs absentes sont grises.
Le changement Standard/Turbo continue à colorer le cadran et la balise, pas les mesures du tracé.
Les anciennes archives sans vitesse/pente ne sont pas enrichies rétroactivement.

## Terrain réel

Source prioritaire : **© IGN, RGE ALTI® 1 m**, Licence Ouverte Etalab, ressource
`ign_rge_alti_par_territoires` de la Géoplateforme. Métropole, Corse et territoires ultramarins
couverts sont présélectionnés par leurs emprises ; les réponses sans donnée restent invalides.
La source combine notamment LiDAR et photogrammétrie : on ne prétend pas que chaque point
est issu du LiDAR. Voir les [métadonnées IGN](https://data.geopf.fr/altimetrie/resources/ign_rge_alti_par_territoires)
et la [documentation officielle de l'API](https://cartes.gouv.fr/aide/fr/guides-utilisateur/utiliser-les-services-de-la-geoplateforme/calcul-altimetrique/).

La grille IGN contient 16 641 mesures réparties en quatre requêtes POST de 4 225 échantillons maximum, avec une cadence
maximale d'une requête par seconde partagée entre les scènes. Timeouts : connexion 5 s,
lecture 15 s ; attente de 2 minutes après erreur, 5 minutes pour une emprise sans données.
Les valeurs `-99999`, absentes ou non finies ne sont jamais affichées comme des altitudes.

La grille IGN mesurée est **129 × 129**, interpolée en **257 × 257** pour le rendu : à
1 100 m de largeur, le pas mesuré est d'environ 8,6 m. Le statut distingue « IGN RGE ALTI 1 M »
et « MAILLE … M ». L'application utilise donc la source métrique, mais ne dessine pas un
sommet tous les mètres. L'altitude GPS, la pente et le profil mesurés ne sont pas remplacés.

Repli pour les points manquants ou le service indisponible :
[Terrain Tiles / Mapzen sur AWS](https://registry.opendata.aws/terrain-tiles/),
[format Terrarium](https://github.com/tilezen/joerd/blob/master/docs/formats.md).
Les pixels PNG sont décodés en mètres, rééchantillonnés sur une grille 65 × 65,
avec une amplification visuelle du relief ×1,8. Le niveau de tuiles diminue pour les grandes zones
ou hautes latitudes. Les zones polaires au-delà de 84° utilisent une grille neutre.

Le parcours est **projeté sur l'altitude du sol**, avec un léger décalage graphique pour le rendre
lisible. Il ne mélange pas directement les altitudes GPS ellipsoïdales et les altitudes DEM.
Les ponts et tunnels ne sont pas modélisés. Le chiffre d'altitude et le profil restent les
mesures GPS filtrées ; ils peuvent donc différer du relief cartographique.

Cache IGN séparé de 64 Mio, sans rafraîchissement temporel automatique. Au démarrage et lors
des déplacements, une grille disque est réutilisée sans réseau dès qu'elle couvre entièrement
la zone visible. Une marge spatiale de 35 % limite les changements de grille. Il n'existe plus
de rechargement périodique à 60 secondes : Internet n'est sollicité que lorsque la vue sort de
la couverture disponible ou qu'aucun cache correspondant n'existe. Cache Mapzen de 64 Mio
et 24 tuiles décodées en mémoire. Les tuiles voisines de la
zone visible sont chargées avec marge, avec rafraîchissement quand le vélo se déplace.
Hors réseau, le cache est utilisé et les zones manquantes ne sont pas inventées.
Les statuts précisent cache, relief partiel, indisponibilité ou attente de position.
Après échec réseau, le chargement continue sur le cache sans multiplier les délais d'attente.

Les requêtes HTTPS IGN transmettent les coordonnées de la grille du terrain visible ; les
requêtes AWS indiquent les numéros des tuiles. Ces fournisseurs connaissent donc la zone. Aucun
trajet enregistré ni journal GPS n'est envoyé. Les sources et licences sont accessibles
par « RELIEF · IGN / MAPZEN » dans le cockpit.

Attributions : [contributeurs et licences Terrain Tiles](https://github.com/tilezen/joerd/blob/master/docs/attribution.md).
Sources principales : USGS (SRTM, GMTED2010, 3DEP), NOAA (ETOPO1), Copernicus / Union européenne
(EU-DEM) et contributeurs régionaux. Les données sont rééchantillonnées pour l'affichage.

## Historique local

Les positions acceptées sont enregistrées séparément de l'affichage dans
`files/rides/ride-<horodatage>*.csv` (un fichier par démarrage du processus et par Reset).
Colonnes des nouveaux journaux : heure, latitude, longitude, altitude GPS, distance cumulée,
début de segment, vitesse GPS km/h (vide si absente), pente % (vide si absente), validité altitude.
Ces fichiers privés contiennent des coordonnées ; contrairement au journal de debug GPS,
ils permettent de reconstruire le trajet. Le tracé affiché couvre la session depuis le dernier
Reset ou démarrage du processus ; les anciens fichiers ne sont pas rechargés automatiquement.
Ils ne sont pas automatiquement supprimés ; ils sont perdus en désinstallant ou effaçant
les données de l'application. Aucun écran de consultation des anciens trajets n'est encore fourni.
La file d'écriture est bornée à 4 096 points ; une saturation exceptionnelle ou une erreur
de stockage est signalée dans le journal système et peut entraîner la perte de points.

## Validation et aperçu

Tests JVM : orientations cardinales, cap au passage de 360°, lignes/boucles/dénivelés avec
plusieurs ratios d'écran, simplification et coupures GPS, décodage des tuiles et interpolation.
L'activity `com.alban.ebike/.CockpitPreviewActivity`, disponible uniquement en debug,
affiche un parcours de démonstration près de Chamonix avec un relief téléchargé réel.
Elle n'envoie aucune commande BLE et ne modifie pas les mesures de l'application.
Le double toucher y permet de comparer les couleurs. Pour revenir au suivi réel, ouvrir
`com.alban.ebike/.MainActivity`.

Validation sur route à poursuivre : qualité du cap en virage, comportement après perte GPS,
lisibilité au soleil, autonomie et chauffe pendant un trajet prolongé.

Vérification du 26 septembre 2026 : 22 tests JVM réussis ; aperçu et suivi réel ouverts sur
le OnePlus connecté, tuiles Terrarium téléchargées et affichées, versions rouge et bleue
inspectées par capture d'écran, sans erreur `EBikeScene`/`AndroidRuntime` dans le relevé.
