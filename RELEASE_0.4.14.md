# E-BikeCockpit 0.4.14

- Une décision de vitesse commune (native ou régression) est calculée avant le déplacement GPS, puis utilisée pour l’affichage et le temps d’activité. Les positions rejetées ne peuvent pas réintroduire une vitesse estimée.
- Un mouvement lent incertain n’est plus transformé automatiquement en arrêt. Une vitesse native précise peut confirmer un départ dès 0,8 m/s avec un déplacement de plus de 3 m ; les contrôles de position restent actifs.
- La reprise du déplacement utilise le point GPS précédent, sans ajouter d’un coup la dérive accumulée pendant l’arrêt. La distance des phases non confirmées n’est pas reconstituée artificiellement.
- Altitude : précision verticale connue et inférieure ou égale à 15 m, trois mesures cohérentes avant validation, rejet des sauts verticaux suspects. Une valeur brute strictement identique pendant au moins 20 s et 30 m est considérée suspecte. Les valeurs brutes restent enregistrées ; l’altitude affichée, la pente et le D+ ne relient pas les périodes invalides.
- Le tracé 3D reste complet en modes 500 m et 2 km ; seule la fenêtre de cadrage change. Le barycentre ignore les points REPOS, avec repli sur le point actuel en l’absence d’activité.

La détection d’altitude répétée est une précaution : elle peut aussi masquer une altitude quantifiée sur une portion réellement plate. Les débuts de parcours et les pertes de signal restent à vérifier sur le terrain.

APK debug compilé pour installation USB uniquement. Aucun nouvel APK ajouté au dépôt ni publié avec cette version, à la demande de l’utilisateur.
