# E-BikeCockpit 0.4.16

- Lissage spatial de l’altitude GPS sur 20 m, avec seuil de retournement adapté à la précision verticale annoncée. Les petites inversions compatibles avec cette incertitude ne deviennent plus immédiatement une descente ou une remontée ; les variations soutenues sont conservées. Le calcul reste causal et peut retarder la détection d’un sommet.
- Profil d’altitude plus fin, contour sombre net et suppression du halo épais. Bande transparente représentant l’incertitude disponible, valeur numérique dans le champ altitude et sur le profil, y compris pendant la sélection d’un point.
- Pour le GPS, la bande utilise la précision verticale annoncée par Android. Pour l’IGN, elle représente uniquement la variation du terrain induite par l’incertitude de position GPS ; la précision verticale intrinsèque du MNT n’est pas fournie. La bande n’est pas une garantie de limites absolues et n’inclut pas le retard du lissage.
- Le calcul IGN réutilise désormais les tuiles fines déjà téléchargées pour la carte, sans devoir attendre une acquisition de grille distincte. Les trous de couverture déclenchent une réévaluation du cache local.
- Correction de la référence verticale GPS : sur Android 14 et suivants, le convertisseur Android hors ligne transforme la hauteur ellipsoïdale en altitude au niveau moyen de la mer, en conservant la précision associée et l’altitude brute dans le journal. La référence MSL n’est pas strictement le NGF IGN ; sans conversion disponible, la source est explicitement « GPS WGS84 ». Aucun décalage arbitraire déduit du terrain n’est appliqué.
- Tracé 3D rendu par des rubans de triangles d’épaisseur constante à l’écran, y compris en vue de parcours complet et sur les GPU limitant les lignes OpenGL à un pixel. Couleurs du parcours et des repos conservées.
- Halo permanent sur la valeur active (vitesse, altitude ou pente) qui pilote les couleurs du tracé, en portrait et paysage.
- Cours d’eau soumis au test de profondeur, comme les autres calques : masqués par les reliefs situés devant eux.
- La source et l’incertitude sont conservées dans les points et les journaux privés. L’altitude brute reste archivée pour l’analyse.

Le trajet du test en cours est préservé : aucune réinstallation pendant son enregistrement. Cette livraison n’ajoute ni ne publie d’APK sur GitHub.
