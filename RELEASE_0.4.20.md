# E-BikeCockpit 0.4.20 — calques, lisibilité et vues communes

Version Android 0.4.20, code 28. ApplicationId et signature de mise à jour conservés.
Cette mise à jour concerne Android uniquement ; aucun nouveau flash ESP32 requis.

## Calques 3D

Les routes, chemins, cours d'eau, bâtiments et parcelles sont projetés sur la même
surface triangulée que celle effectivement dessinée. Une interpolation différente
pour le terrain et les objets pouvait placer les objets sous le maillage, notamment
en vue d'ensemble. Un test avec relief synthétique irrégulier reproduit ce défaut.
Le cache de géométrie des objets change de version pour recalculer ces projections.

Les traits des objets sont maintenant des rubans calculés en pixels écran, comme
la trace. Leur visibilité ne dépend plus de la largeur maximale des lignes OpenGL
du téléphone. Le relief continue d'occulter les objets situés derrière une colline.
Les projections interrompues peuvent être annulées pendant leur calcul pour ne
pas continuer à mobiliser du CPU lors d'un changement de vue.

Les lignes MAP dans le journal privé indiquent les nombres de segments projetés
par catégorie, sans coordonnées. Elles permettent de distinguer absence de données
et problème de rendu. La disponibilité des données et les limites de densité restent
applicables ; le cadastre reste conditionné à son activation.

## Contraste et dézoom

Le contraste conserve un renforcement minimal de 18 % même sans capteur ou dans
l'obscurité. La commande augmente ensuite linéairement jusqu'au maximum à 8 000 lux,
avec l'amortissement temporel du capteur. Les composants partagent cette mesure.

Le facteur minimal du zoom tactile passe de 0,5 à 0,15 : la caméra peut s'éloigner
environ 3,3 fois plus qu'auparavant. Les bornes d'inclinaison et le retour existant
au cadrage automatique après interaction sont conservés.

## Graphique et portion de trajet

Une seule préférence de distance commande maintenant les deux vues, en portrait
comme en paysage : **500 m → 2 km → tout le trajet → 500 m**. Un toucher dans la
3D ou sur le graphique change ce même choix. Le tracé 3D, son cadrage et les points
du graphique utilisent la même sélection, y compris la dernière position reçue.
Les mesures du trajet complet restent conservées.

La courbe d'altitude utilise des chemins continus à trait de 1,6 dp, avec un contour
sombre de 2,8 dp. Ces épaisseurs ne dépendent ni de la distance, ni du nombre de
points, ni de la luminosité. Les anciens traits ronds dessinés segment par segment
pouvaient se surimprimer lorsque les points étaient très denses. La bande translucide
d'incertitude reste une information séparée de l'épaisseur du trait.

Validation automatisée : projection au-dessus du terrain rendu, maintien des calques
en vue d'ensemble, bornes du zoom, séquence des vues, conservation de la dernière
position, sélection commune du tracé et linéarité du contraste.
