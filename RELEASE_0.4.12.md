# E-BikeCockpit 0.4.12

- Profils par ESP32 : roue, calibration et préférences propres à chaque VAE, restaurés à la reconnexion. Profil séparé sans ESP ; nom du vélo modifiable dans Settings.
- GPS : demande de positions fraîches à 500 ms (fréquence effective dépendant du téléphone), confirmation plus rapide avec vitesse native précise et déplacement cohérent. Les garde-fous contre les sauts et positions périmées restent actifs.
- Repos : archivage des positions brutes, mesures et paquets BLE pendant les pauses ; coloration magenta des segments du tracé 3D et du profil. Les points rejetés sont marqués dans le journal et exclus du dessin.
- Arc du compteur épaissi, textes plus contrastés et atténuation du brouillard 3D en forte luminosité.
- Caméra : suivi du point actuel en mouvement, pivot actuel en manipulation 500 m/2 km et barycentre en Tout ; pivot au tiers supérieur, perspective accentuée en mouvement.
- Cours d’eau : projection plus fine, recalcul au changement de mode, trait renforcé et rendu visible au-dessus du relief.
- Barre d’état transparente et suppression de la marge globale liée à l’encoche en paysage. Seules les commandes d’en-tête gardent une protection contre l’encoche.

Les délais initiaux du récepteur GNSS dépendent encore des conditions de réception. La fréquence demandée ne garantit pas une mesure matérielle toutes les 500 ms.
