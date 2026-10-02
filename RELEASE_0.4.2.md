# E-BikeCockpit 0.4.2 — versionCode 10

- Préchargement 360° : toucher le bouton central « 360° » de la boussole couvre tout le disque du rayon choisi (2–50 km). Toucher une direction rétablit le secteur de 90°. Le disque complet est coloré quand il est sélectionné.
- Dans Options → Hors ligne, affichage des octets téléchargés par les sources cartographiques depuis l’ouverture de cette session et de la taille réelle du cache cartes/relief, géométries dérivées comprises. Les octets reçus sont comptabilisés pendant les réponses réseau, sans les en-têtes HTTP ; ils incluent le chargement automatique et les réponses incomplètes. Un accès au cache n’augmente pas ce compteur. Le cache disque est rafraîchi toutes les deux secondes pendant l’affichage de cet onglet. La taille finale à télécharger n’est pas connue à l’avance.
- Bouton « Effacer le cache » avec confirmation dans l’application. Il interrompt et attend les téléchargements en cours, vide les caches téléchargés (y compris zones protégées et anciens caches IGN/Mapzen) et leur mémoire. Les géométries dérivées sont aussi effacées, avec suspension de leurs écritures pendant l’effacement. Les trajets et réglages sont conservés. Le chargement automatique reprend à la fermeture des options ; les cartes peuvent alors remplir à nouveau le cache.
- Chronomètre agrandi de 17 à 28 sp, chiffres plus épais.
- Bouton à deux lignes « Setting » / « ROUE … » entièrement cliquable en portrait et paysage. La valeur de roue reste la circonférence configurée en mm.

Identifiant Android et signatures conservés. APK debug pour la mise à jour USB du téléphone de test, APK release avec la clé release existante.

Validation : 79 tests unitaires réussis ; APK debug/release et lint vital release compilés avec succès. Version debug installée par mise à jour USB sur le OnePlus 8T, démarrage et version 0.4.2 / code 10 vérifiés. Aucun cache réel du téléphone effacé et aucune longue session de tests UI.
