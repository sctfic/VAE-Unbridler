> État historique de la 0.4.18 et conception initiale. Les sources ont depuis été réintégrées et les changements réalisés sont décrits dans [la 0.4.19](RELEASE_0.4.19.md).

# Mesures affichées et approche proposée — 13 novembre 2026

Ce résumé décrit l'APK 0.4.18 effectivement installé, dont l'empreinte a été
comparée à celle du paquet conservé dans `android-app/build/phone-update-source`.
Ses sources comprennent les évolutions GPS/altimétriques locales conservées lors
de l'installation. Le code Android à la racine du dépôt n'intègre pas encore
toutes ces évolutions : il faut les préserver avant toute nouvelle construction.

Les sections « Approche proposée » décrivent le travail envisagé, pas des fonctions
déjà installées. Le filtre firmware 99 km/h / +15 km/h/s a été compilé lors de la
précédente intervention mais n'a pas été flashé dans cette conversation.

## Fonctionnement actuel

### Grande vitesse, normalement libellée GPS

1. Le fournisseur Android Fused Location fournit une vitesse native en m/s, sa
   précision, la date de mesure et la précision horizontale. La demande vise une
   mesure toutes les 500 ms ; la fréquence effective dépend du fournisseur.
2. Rejet des mesures périmées de plus de 4 secondes, positions invalides ou
   précision horizontale supérieure à 20 m. La vitesse native doit être comprise
   entre 0 et environ 120 km/h et disposer d'une incertitude exploitable.
3. L'incertitude maximale admise est de 1 m/s au démarrage ; en mouvement, 30 % de
   la vitesse, avec des bornes de 1 à 3 m/s. Le démarrage exige une confirmation
   du déplacement, avec une voie plus rapide pour les mesures très précises.
4. Une médiane des trois dernières vitesses et un filtre exponentiel dépendant
   du temps écoulé et de l'incertitude atténuent les variations. Un arrêt natif
   bien mesuré, au plus 0,5 m/s, est ramené à zéro. Une mesure momentanément
   incertaine peut conserver la vitesse précédente pendant 2 secondes.
5. Si la vitesse native est indisponible ou maintenue, une régression pondérée
   des positions sur au plus 6 secondes peut fournir une estimation. Elle exige
   au moins trois positions sur 2 secondes et rejette les ajustements incohérents.
   Cette estimation est signalée par l'effet visuel d'approximation.
6. La validation du déplacement contrôle aussi la cohérence des positions. Une
   position refusée invalide la vitesse issue de cette mesure.
7. La grande valeur privilégie cette vitesse GPS valide, y compris un zéro valide.
   Sinon elle affiche la vitesse roue si le Bluetooth est connecté, avec le
   libellé ROUE ; sinon un tiret. Une vitesse GPS sans nouvelle mesure fiable
   devient indisponible après 4 secondes.
8. La valeur est animée vers sa cible sur 420 ms et arrondie à l'entier. Cette
   animation n'est pas une moyenne temporelle sur 2 secondes.

### Vitesse roue

1. L'aimant provoque des fronts descendants sur GPIO 5 de l'ESP32. Le diagnostic
   brut mesure chaque intervalle entre fronts, avant rejet.
2. Le firmware détermine une période de révolution retenue. La conversion est :
   `vitesse_km/h = circonférence_mm × 3600 / période_microsecondes`.
3. La circonférence provient du réglage associé au vélo et de la configuration
   conservée par l'ESP32. Une proposition de calibration à partir d'un déplacement
   GPS suffisamment long et rectiligne peut être présentée ; son application
   nécessite l'action « Appliquer cette calibration ». Elle ne corrige pas chaque
   valeur roue automatiquement.
4. L'ESP32 transmet la vitesse en centi-km/h et la période en microsecondes par
   Bluetooth. L'app divise la vitesse par 100, la conserve puis l'arrondit à
   l'entier pour le petit compteur. Il n'y a pas de lissage temporel spécifique
   de ce compteur ni de garde Android à 99 km/h dans l'APK installé.
5. À la déconnexion, les valeurs sont remises à zéro dans l'état et les petits
   compteurs sont masqués. Le délai d'arrêt du firmware remet aussi la vitesse
   à zéro en l'absence de fronts validés.

### Vitesse moteur

1. Elle est calculée par l'ESP32 à partir de la période roue retenue et du mode.
2. Standard : la vitesse moteur commandée égale la vitesse roue.
3. Turbo : jusqu'au seuil configuré, 22,20 km/h par défaut, elle égale la roue.
   Au-dessus, la règle existante est `20 + vitesse_roue / 10`, avec arrondi entier
   interne en centi-km/h. Ce calcul n'est pas une limitation universelle à 25 km/h.
4. La période commandée est calculée avec la même circonférence. GPIO 6 produit
   les impulsions actives à l'état bas de 2 ms pour le contrôleur.
5. Le compteur MOTEUR affiche la valeur commandée transmise par l'ESP32, arrondie
   à l'entier. Il ne reçoit ni la vitesse décodée par le Kiox ni une mesure
   électrique indépendante de la sortie. Le compteur d'impulsions renseigne
   l'exécution des débuts de pulses, sans en donner chaque intervalle exact.

### Altitude

1. Priorité à l'altitude du terrain IGN interpolée à la position validée, si une
   grille réelle assez fine est disponible (pas au plus 5,01 m) et si la précision
   horizontale est au plus de 10 m. Il s'agit de l'altitude du terrain à cette
   position, pas d'une mesure de hauteur par le téléphone.
2. À défaut, utilisation de l'altitude Android : altitude au niveau moyen de la
   mer si fournie sous Android 14+, sinon altitude ellipsoïdale WGS84, signalée
   comme telle. Il n'y a pas de conversion inventée entre ces références.
3. Pour ce repli GPS : altitude et incertitude verticales finies, incertitude au
   plus de 15 m et trois confirmations. Les sauts verticaux excessifs sont rejetés.
4. Le changement de source ou de segment réinitialise le filtre. L'altitude est
   lissée selon la distance parcourue, avec un horizon de 8 m pour IGN et de 20 m
   pour GPS. Les petits retournements de tendance sont amortis selon l'incertitude.
   Sans progression spatiale, la valeur est conservée pour éviter la dérive à l'arrêt.
5. Affichage arrondi au mètre, avec la source et une incertitude lorsqu'elle est
   disponible. L'incertitude IGN affichée reflète l'effet de l'incertitude de
   position sur le relief ; elle n'inclut pas une erreur verticale IGN inconnue.
   Sans altitude exploitable, affichage d'un tiret.

### Pente

1. La pente est calculée dans l'app à partir du profil altitude/distance validé,
   pas à partir du capteur roue, du couple moteur ou d'un inclinomètre.
2. Une régression des altitudes sur les distances utilise les derniers points
   distincts, dix par défaut et réglables entre 3 et 30 ; au moins trois sont
   nécessaires. Le pourcentage vaut 100 fois la pente de cette régression.
3. Une rupture de données ou de source interrompt le calcul. Le résultat est
   retenu seulement si la précision horizontale est au plus de 10 m et si la
   longueur analysée atteint au moins 6 m et deux fois cette précision.
4. Affichage signé avec une décimale, ou un tiret sans estimation valide. Ce
   calcul porte sur une portion du trajet et peut donc suivre un changement de
   pente avec retard. La correction centrée de l'historique est affectée au milieu
   de la portion analysée, sans inventer une pente instantanée au point courant.

En consultation d'un point historique, le grand compteur, l'altitude et la pente
proviennent des données enregistrées à ce point ; les petits compteurs roue et
moteur ne sont pas affichés. Le zoom et l'exagération verticale 3D ne recalculent
pas les mesures physiques d'altitude ou de pente.

## Approche proposée : nettoyage et lissage des vitesses

- Conserver les mesures et diagnostics bruts. Enregistrer séparément valeur
  reçue, décision de rejet, valeur retenue et valeur affichée.
- Faire appliquer le filtre matériel préparé par l'ESP32 : plafond roue de
  99 km/h et accélération positive maximale de 15 km/h/s, sans rejet des freinages.
  En cas de rejet, le VAE reçoit la dernière cadence validée jusqu'au délai
  normal d'arrêt. Un filtre visuel Android ne peut pas remplacer cette protection.
- Ajouter une garde Android aux valeurs affichées : valeurs non finies,
  négatives, au-delà du plafond choisi ou incompatibles avec leur période et leur
  circonférence. Valider l'accélération roue selon le temps réellement écoulé.
  Le moteur doit être vérifié selon son mode : une bascule volontaire de mode
  peut provoquer un changement de consigne légitime.
- Rejeter, plutôt que remplacer par 99, une valeur telle que 181. Une mauvaise
  mesure n'entre pas dans la fenêtre de lissage. Ne pas recaler la roue sur le GPS,
  notamment lors des essais statiques.
- Utiliser une moyenne glissante pondérée par la durée sur les deux dernières
  secondes pour chacun des trois affichages, avec des historiques séparés. La
  pondération évite que les trames BLE à 5 Hz pèsent artificiellement plus que les
  mesures GPS moins fréquentes.
- Un arrêt confirmé remet immédiatement l'affichage à zéro et vide sa fenêtre.
  Une décélération n'est jamais rejetée. Une mesure invalide ne doit pas être
  transformée en faux zéro ; le maintien est borné, puis la valeur devient
  indisponible. Réinitialiser les fenêtres à la reconnexion et au changement de
  source du grand compteur.
- Éviter d'empiler un nouveau long effet d'animation avec la fenêtre de 2 s.
  Le GPS possède déjà un filtrage de mesure : vérifier le retard total, distinct
  de la seule largeur de la fenêtre d'affichage. Une moyenne ne supprime pas les
  pics si ceux-ci y ont déjà été admis.
- Ne pas laisser ce lissage d'affichage modifier la commande moteur, les
  mesures brutes archivées, la distance, l'altitude ou la pente.

## Approche proposée : préchargement de la carte 3D

L'acquisition actuelle précharge déjà des tuiles de relief proches du focus.
Les objets OSM sont demandés séparément pour une zone locale d'environ 900 m
de demi-côté ; le cadastre utilise encore sa propre demande. Ces acquisitions ne
constituent pas une anticipation coordonnée de tous les calques actifs.

1. Construire une liste commune des tuiles nécessaires à la zone visible et une
   couronne de zones limitrophes, en respectant la taille propre à chaque source.
2. Couvrir relief IGN et courbes de niveau dérivées, routes, chemins, cours d'eau,
   bâtiments OSM et cadastre si leurs calques sont actifs. Les courbes de niveau
   sont générées depuis le relief ; elles ne nécessitent pas un autre téléchargement.
3. Donner priorité à la zone manquante affichée, puis aux voisins dans la direction
   de déplacement, puis aux autres voisins. Garder une couronne complète pour
   couvrir un virage. L'ordre dépend du déplacement réel, pas d'un point historique
   sélectionné dans le profil.
4. Partager les requêtes déjà en cours et réutiliser les caches avant le réseau.
   Les téléchargements utiles doivent pouvoir terminer et être sauvegardés même
   si le centre de la vue change pendant leur exécution.
5. Recalculer les besoins au passage d'une limite de tuile ou à l'activation d'un
   calque, sans recommencer tout le lot à chaque fix. Réutiliser les lots OSM
   partagés par plusieurs calques lorsque cela évite des demandes supplémentaires.
6. Borner la concurrence, conserver les temporisations réseau et les budgets de
   cache, appliquer un délai avant les reprises après échec et suspendre les
   tentatives réseau sans connexion. Le préchargement automatique ne doit pas
   épingler sans limite les données comme une zone hors ligne choisie manuellement.
7. Fusionner progressivement les tuiles et préparer leur géométrie, en conservant
   les calques déjà visibles pendant les acquisitions. Un simple téléchargement
   sans mise à jour du rendu ne suffit pas.

Validation prévue : pics isolés 181 km/h, arrêt, freinage, accélération normale,
bascule Standard/Turbo, perte BLE, changement de source GPS/roue, fréquences
irrégulières ; déplacement entre tuiles, virage, changement de calques, échec
réseau et reprise hors ligne sans requêtes en double.
