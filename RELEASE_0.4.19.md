# E-BikeCockpit 0.4.19

Version Android 0.4.19, code 27, applicationId et signature de mise à jour conservés.
Les sources de l'APK 0.4.18 effectivement installé ont été réintégrées avant ces
modifications pour préserver les profils, la calibration et les fonctions de trajet.

## Affichage des vitesses

Une vitesse de roue positive et cohérente est prioritaire face à un zéro GPS.
Le grand compteur indique alors ROUE. Une vitesse GPS positive reste prioritaire.
Le grand chiffre affiche une décimale plus petite sur la même ligne.

Chaque affichage possède sa moyenne glissante sur deux secondes, pondérée par le
temps et indépendante de la fréquence des messages. Le GPS conserve ses contrôles
de qualité existants : la fenêtre d'affichage n'est pas une garantie de retard
total limité à deux secondes. L'ancienne animation de 420 ms est retirée.

Les valeurs non finies, négatives ou supérieures à 99 km/h sont rejetées avant
lissage. Les vitesses BLE sont aussi confrontées aux périodes, à la circonférence
et à la règle de simulation annoncée. Les accélérations positives au-delà de
15 km/h/s sont rejetées ; les décélérations sont acceptées. Un zéro accepté vide
immédiatement la fenêtre. Le maintien sans nouvelle valeur acceptée expire après
deux secondes ; la déconnexion réinitialise les fenêtres roue/moteur. Une bascule
de mode réinitialise le filtre moteur pour accepter un changement de consigne.

Les données reçues restent archivées. Des lignes DISPLAY distinguent les valeurs
affichées des mesures brutes BLE/SENSOR. Le lissage d'affichage ne commande pas
l'ESP32 et ne modifie pas les mesures physiques d'altitude ou de pente.

## Altitude

IGN reste sans offset fixe. La hauteur ellipsoïdale WGS84 brute d'Android et
l'altitude du terrain IGN dans son référentiel de nivellement ne partagent pas
le même zéro. Un décalage de l'ordre de 50 m peut provenir de cette distinction,
ainsi que du bruit GPS. La conversion dépend du lieu : ajouter une constante à
IGN serait incorrect. L'app conserve la source explicite IGN, GPS ou GPS WGS84,
et une rupture du profil au changement de source.

Sources : [Android Location](https://developer.android.com/reference/android/location/Location)
et [grilles de conversion IGN](https://geodesie.ign.fr/grilles-de-conversion).

## Carte 3D

Le chargement visible OSM utilise des requêtes par tuile partagées avec le
préchargement et l'acquisition hors ligne. Les calques sont publiés progressivement.
L'anticipation couvre les tuiles fines de relief proches du vélo et les voisines
du fond de carte visible. Les courbes de niveau sont dérivées du relief.

Si des calques OSM sont actifs, leur couverture visible est entourée d'une couronne
complète supplémentaire. Routes, chemins, eau et bâtiments partagent les mêmes
lots ; un téléchargement peut donc contenir des catégories masquées. Le cadastre,
s'il est actif, anticipe la zone locale de 3×3 tuiles par une couronne supplémentaire
(5×5). Il reste local pour borner les volumes de parcelles dans les grandes vues.

Le centre et les voisins proches passent en premier, avec priorité directionnelle
sans suppression des autres directions. Deux acquisitions d'anticipation peuvent
s'exécuter simultanément, avec réutilisation des caches, temporisations réseau et
réessai différé après échec. Les travaux utiles en cours peuvent terminer malgré
un déplacement de la vue. Les caches restent bornés, sans épinglage automatique
illimité. Les limites de réseau, couverture et densité des sources restent applicables.

## Identification des filtres ESP32

Les lignes SENSOR indiquent désormais limit99, acceleration15 et holdPrevious.
Un ancien firmware annonce implicitement ces capacités absentes ; la nouvelle
révision préparée les annonce présentes. Cela permettra de contrôler le filtre
réellement actif après flash, indépendamment de la version Android.

Installer l'app ne met pas à jour le firmware ESP32 : le flash reste une étape séparée.
