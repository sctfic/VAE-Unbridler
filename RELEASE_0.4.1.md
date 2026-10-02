# E-BikeCockpit 0.4.1 — versionCode 9

- Glisser sur le profil sélectionne un point entouré d’un cercle blanc bordé de noir. Le même cercle, de même taille à l’écran, identifie ce point dans la scène 3D. Il reste le centre d’orbite ; un toucher du profil rétablit le cadrage automatique.
- Les vitesses GPS recalculées n’affichent plus le signe d’approximation ni le flou. Des bandes horizontales des chiffres se décalent aléatoirement de quelques pixels vers la gauche ou la droite, toutes les 140 ms. L’animation s’arrête quand l’écran est en arrière-plan ou qu’une vitesse native reprend.
- Pente : régression altitude/distance des 10 derniers points GPS retenus en déplacement, réglable de 3 à 30 depuis les réglages roue/chronomètre/pente. Aucun seuil de distance de 20 m ni rééchantillonnage. Trois points distincts au minimum sont requis ; les lacunes d’altitude et ruptures GPS interrompent la fenêtre. Les arrêts restent exclus et le lissage spatial existant de l’altitude est conservé.
- Plus de points stabilisent la pente mais ralentissent sa réaction. À vitesse basse, la courte distance couverte amplifie les erreurs d’altitude GPS ; à vitesse élevée, la fenêtre couvre davantage de terrain. Cette pente reste une estimation issue du GPS, sans capteur barométrique.

ApplicationId et clés de signature inchangés. Les APK debug (compatible avec le téléphone de test) et release sont fournis dans `releases/`, avec leurs empreintes SHA-256.

Validation : 75 tests unitaires réussis, builds debug/release et lint vital release réussis. APK debug installé par mise à jour USB et activité lancée sur le OnePlus 8T ; version 0.4.1 / code 9 vérifiée. Pas de session prolongée de tests UI.
