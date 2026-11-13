# Hardware v1 — capture roue et diagnostic Android 0.4.18

## Constat et limites

En roulage, le GPS du téléphone est cohérent, alors que roue, moteur et Kiox
affichent une vitesse trop faible. Sur support, les vitesses roue/moteur/Kiox
restent synchronisées à 19 et 30 km/h. Le Kiox étant également affecté, un défaut
d'affichage Android seul ne suffit pas à expliquer le problème.

Les anciens journaux GPS ne contiennent pas les impulsions ni la télémétrie BLE.
Ils ne permettent donc pas d'établir la cause physique du défaut ; les correctifs
ci-dessous ne prouvent pas sa résolution sur le vélo.

## Firmware

- Une impulsion trop rapprochée, rejetée, ne déplace plus la référence temporelle
  de la prochaine révolution.
- Après arrêt ou interruption, la première impulsion amorce une nouvelle mesure :
  le temps d'arrêt n'est plus interprété comme une révolution très lente.
- L'acquisition initiale attend jusqu'à 15 secondes pour permettre un départ lent
  (environ 8,5 secondes par tour à 1 km/h avec une roue de 2,36 m).
- Le délai d'arrêt est vérifié même lorsque la file reçoit des impulsions rejetées.
- Les périodes correspondant à plus de 99 km/h sont rejetées, en fonction de la
  circonférence configurée. Une fois une période acquise, les augmentations de
  vitesse sont limitées à 15 km/h par seconde écoulée depuis le dernier front
  validé. Les décélérations ne sont pas limitées par ce contrôle.
- Le générateur maintient une cadence continue fondée sur la dernière période
  validée, également en Standard. Un rejet ne déclenche aucune impulsion immédiate
  supplémentaire. Le délai d'arrêt habituel met fin à ce maintien : au moins
  1,5 seconde ou trois périodes validées sans nouveau front accepté. Le premier
  intervalle après réamorçage n'a pas de vitesse précédente pour contrôler
  l'accélération, mais reste soumis à la limite de 99 km/h.

Le câblage GPIO 5 et la sortie moteur GPIO 6 open drain du Hardware v1 sont
conservés. Les impulsions moteur restent de 2 ms. Le protocole BLE reste en v1.
Une absence réelle d'impulsions ne peut pas être reconstruite par ce filtre.

## Application

Version 0.4.18, code 26. Le journal privé tournant existant conserve désormais les
statuts BLE et chaque télémétrie reçue avec périodes, compteurs, mode, circonférence
et vitesse GPS au même instant. Il reste borné à environ 12 Mio et sans latitude
ni longitude. Les vitesses roue/moteur continuent de provenir de l'ESP32.

Le firmware mesure aussi l'intervalle brut entre tous les fronts descendants du
capteur GPIO 5, directement dans l'interruption et avant filtrage. Une nouvelle
caractéristique BLE optionnelle transmet à 5 Hz le dernier intervalle brut et les
compteurs de fronts capturés, rejetés et perdus dans la file. L'app les écrit dans
des lignes `SENSOR` (`rawIntervalUs`, en microsecondes), associées aux lignes `BLE`
par `seq`. Les anciennes trames de télémétrie restent inchangées. Il faut mettre à
jour les deux logiciels pour obtenir le diagnostic brut ; les compteurs indiquent
les fronts entre les instantanés, sans garantir une trace exhaustive de chacun.

## Vérification sur le vélo

1. Sur support, vérifier Standard à 19 puis 30 km/h, l'arrêt et la reprise.
   Vérifier séparément les transitions Standard/Turbo après mise à jour firmware.
2. Pousser le vélo, roues au sol et assistance désactivée. Si les impulsions se
   perdent déjà, examiner l'alignement aimant/capteur sous charge et la continuité
   du câble et des connecteurs.
3. Comparer ensuite avec assistance. Un défaut apparaissant seulement dans cette
   condition demande une mesure du signal GPIO 5, de la masse et de l'alimentation
   pour distinguer une perturbation électrique d'un défaut mécanique.
4. Récupérer les journaux après reproduction, sans désinstaller l'application.
   Une séquence régulière et un temps de fonctionnement croissant, avec compteurs
   roue/moteur qui stagnent, orientent vers la capture du capteur. Des trous de
   réception orientent vers BLE ; un temps de fonctionnement qui repart de zéro
   peut indiquer un redémarrage de l'ESP32, à distinguer du bouclage du compteur.

Les journaux ne montrent ni la forme électrique des impulsions ni l'état physique
de l'aimant : ces contrôles restent nécessaires si le défaut persiste.
