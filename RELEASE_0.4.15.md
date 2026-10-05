# E-BikeCockpit 0.4.15

- Altitude IGN prioritaire pour l’affichage, la pente, le profil et le dénivelé, avec repli sur le GPS validé lorsque le terrain est indisponible ou la localisation horizontale trop incertaine.
- Grille de mesure dédiée de 320 m de côté, échantillonnée tous les 5 m, indépendante du mode 500 m / 2 km / Tout. Réutilisation des caches IGN suffisamment fins ; acquisition anticipée près des limites.
- Régression sur les derniers points d’activité. Pente masquée si la précision horizontale dépasse 10 m ou si la distance couverte par les points est insuffisante par rapport à cette incertitude.
- Source IGN / GPS indiquée sur les champs altitude et pente, conservée dans chaque point et dans le journal. Les changements de source interrompent la pente et le dénivelé afin de ne pas additionner les écarts de référence altimétrique.
- Suppression du rejet automatique d’une altitude répétée : une valeur constante peut correspondre à un terrain plat ou à une mesure quantifiée. Les contrôles de précision verticale et de saut GPS restent actifs.

Le MNT représente le sol, pas nécessairement la chaussée d’un pont ou d’un tunnel. Hors ligne, le calcul IGN nécessite une grille suffisamment fine déjà en cache ; sinon le GPS prend le relais. Installation USB uniquement pour cette livraison.
