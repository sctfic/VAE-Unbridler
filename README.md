> Android 0.4.32 (code 40), firmware matériel V1 1.0.10 (code OTA 9).
> Acquisition/confirmation ESP32 et lissage d’affichage Android sont séparés ;
> voir [le protocole BLE courant](BLE_PROTOCOL.md).

# E-BikeCockpit — télémétrie et simulateur d'impulsions

Version Android **0.4.32** — auteur **Lopez Alban**.

Les journaux GPS, trajet et Bluetooth ainsi que le panneau DEBUG sont supprimés.
Voir [le chargement et les caches 3D](MAP_LOADING.md) et [les changements 0.4.23](RELEASE_0.4.23.md).

Un appui long sur la scène 3D ouvre les options d'affichage : lignes de niveau,
cours d'eau, routes, chemins et bâtiments. Les choix sont mémorisés localement.
Les bâtiments sont représentés par leurs contours au sol (ways OpenStreetMap),
sans hauteur inventée ; toutes les couches cochées restent affichées en vue d'ensemble.
Les données OSM enrichies utilisent un cache v2 :
le premier chargement peut nécessiter Internet, les suivants réutilisent le cache.
Les couches OSM partagent leur cache. L'option cadastre charge séparément les
parcelles IGN/DGFiP proches du GPS et affiche leur section et numéro (pas les
propriétaires). Ces données sont ensuite conservées en cache local.

Le projet contient deux sous-projets indépendants :

- `firmware-esp32/` : firmware ESP-IDF pour ESP32-S3 Zero. Le contact magnétique de roue est lu sur GPIO 5. Le générateur d'impulsions moteur fonctionne sur GPIO 6 dans une tâche temps réel séparée de Bluetooth.
- `android-app/` : application Android native, tableau de bord plein écran et service persistant Bluetooth/GPS.

Le protocole commun est documenté dans [`BLE_PROTOCOL.md`](BLE_PROTOCOL.md) et le câblage dans [`firmware-esp32/HARDWARE.md`](firmware-esp32/HARDWARE.md).

## Fonctions disponibles

Le [cockpit holographique](COCKPIT.md) affiche les grandes valeurs sur une scène 3D native,
avec relief réel filaire, courbes de niveau, cadrage automatique et historique local du trajet.

- vitesse GPS principale animée, vitesse de roue et vitesse transmise au moteur ;
- profil d'altitude 500 m / complet / 2 km (un toucher pour changer) et pente par régression sur les 10 derniers points GPS valides en déplacement (3 à 30 réglables) ; l’axe horizontal suit la distance GPS validée, profil et pente figés à l’arrêt ;
- distance et trace GPS 3D orientée selon les derniers points ;
- GPS, altitude et tracé actifs dès l’ouverture de l’application, même sans ESP32 ;
- circonférence de roue ou diamètre réglable depuis l'application et enregistré dans l'ESP32 ;
- maintien de l'écran allumé et verrou CPU pendant le suivi GPS, indépendamment du Bluetooth ;
- association initiale avec l'ESP32, puis détection automatique de sa présence sur Android 12 ou plus récent.

## Sécurité électrique importante

### Modes Standard / Turbo

Un double toucher dans la zone des vitesses (hors boutons de réglage) bascule le mode
si l’ESP32 est connecté et dispose du firmware compatible. **Standard** (halo et anneau
bleus) transmet les impulsions au rythme réel ; **Turbo** (rouges) active la simulation
au-dessus du seuil configuré. Le nom est affiché dans le cadran et confirmé par la
télémétrie, indépendamment de la vitesse instantanée. Sans connexion, le mode est inconnu.
L’ESP32 démarre toujours en Standard ; une déconnexion Bluetooth conserve le mode en cours.
Les deux logiciels doivent être mis à jour. Vérifier les transitions sur banc, à l’arrêt,
avant utilisation ; le double toucher peut aussi être reconnu en roulant.

GPIO 6 est une sortie à drain ouvert : impulsion à 0 (signal relié à la masse), repos à 1 (haute impédance). Ce firmware concerne la carte ESP32-S3 Zero actuelle avec Bluetooth Standard/Turbo, pas une nouvelle carte.

La liaison directe suppose une entrée moteur à rappel 3,3 V compatible, une masse de signal commune et un courant limité. Une mesure à 3,3 V à vide ne valide pas à elle seule les transitoires ni le comportement ESP32 éteint. **Ne jamais court-circuiter VCC avec GND.** Voir les conditions et les tests dans [`firmware-esp32/HARDWARE.md`](firmware-esp32/HARDWARE.md).

## Installer l'application Android

### Vitesse et données du trajet

La grande vitesse privilégie le GPS valide ou estimé, puis la roue connectée.
Une vitesse roue positive prend la priorité sur un zéro GPS valide.
La trace, le profil et les valeurs historiques nécessaires au tableau de bord
restent en mémoire pendant le trajet. Aucun journal GPS/CSV/Bluetooth n'est écrit,
exporté ou transmis ; le firmware ne produit plus de logs.

1. Ouvrir le dossier `android-app` dans Android Studio et attendre la fin de la synchronisation Gradle.
2. Activer les options développeur et le débogage USB sur le téléphone, puis le brancher.
3. Choisir le téléphone dans la barre supérieure d'Android Studio et cliquer sur **Run**.
4. Au premier lancement, accepter Bluetooth, localisation, notifications et la localisation « Toujours autoriser ».
5. Allumer l'ESP32, toucher **ESP32** une seule fois dans l'application, puis confirmer l'association proposée par Android.

Le GPS démarre à l’ouverture de l’application une fois la localisation précise autorisée. L’altitude, le profil, la distance et le tracé continuent sans ESP32 et lors d’une déconnexion Bluetooth. Seules les vitesses de roue et moteur dépendent de la carte. La notification persistante permet d’arrêter le suivi. Les points peu précis (plus de 20 m d’incertitude) sont ignorés ; à l’intérieur, il peut être nécessaire de sortir pour obtenir un tracé. Une altitude absente n’est pas affichée comme une altitude mesurée de zéro.

Le bouton **ESP32** est gris hors connexion et cyan gras lorsqu’il est connecté. La vitesse GPS privilégie la mesure native Android. En son absence ou si elle est refusée, une régression pondérée des coordonnées sur les six dernières secondes fournit une estimation signalée par des bandes de chiffres décalées aléatoirement. Trois positions sur au moins deux secondes sont nécessaires ; les positions périmées, imprécises ou incohérentes restent rejetées. Le départ demande trois mesures sur au moins 1,5 seconde et un déplacement supérieur à l’incertitude cumulée. L’incertitude maximale vaut 1 m/s au départ, puis 30 % de la vitesse (bornée entre 1 et 3 m/s) en mouvement. Une médiane de trois mesures et un filtre exponentiel court atténuent les pics. Un arrêt bien mesuré remet immédiatement le filtre à zéro ; les petites vitesses ambiguës demandent deux mesures consécutives. Ces seuils peuvent masquer les déplacements très lents.

Une vitesse temporairement incertaine est maintenue au maximum deux secondes, puis utilise la régression positionnelle (repli roue connectée si aucune estimation, sinon **—**). Sans nouveau fix, le délai de péremption reste de quatre secondes. La trace et la distance sont validées séparément : des positions cohérentes continuent le parcours même sans vitesse native fiable. Un déplacement positionnel doit dépasser l’incertitude sur une fenêtre bornée ; un arrêt confirmé fige la distance. Seuls une perte de positions de plus de quatre secondes, un point invalide ou un saut excessif créent une interruption. Ce filtre ne remplace pas un essai réel à l’extérieur.

Après association, la présence de l’ESP32 peut aussi démarrer le service ; pour ce démarrage en arrière-plan, accorder la localisation « Toujours autoriser ». Android peut empêcher une application d'ouvrir une fenêtre depuis l'arrière-plan selon la version du système ou le fabricant ; dans ce cas, la notification persistante **E-Bike** ouvre le tableau de bord en un toucher.

APK déjà construit : `android-app/app/build/outputs/apk/debug/app-debug.apk`.

### Ouverture automatique au démarrage de l’ESP32

Sur Android 12+, confirmer une fois l’association système proposée par E-Bike (ou toucher **ESP32** pour la relancer). Une connexion BLE directe ne suffit pas : l’association doit être enregistrée par Android. Durant cette étape, l’application libère sa connexion pour que la carte soit visible dans la recherche système.

Quand la carte réapparaît, Android réveille le service compagnon ; celui-ci demande l’ouverture du tableau de bord si l’écran est allumé et déverrouillé. Le mode plein écran est réappliqué lorsque la fenêtre revient au premier plan. Le service reste actif si la tâche est retirée des applications récentes. Le téléphone et l’ESP32 n’ont pas besoin d’être branchés ensemble au PC : la carte doit simplement être alimentée.

Un **Forcer l’arrêt** dans les paramètres Android peut bloquer le réveil jusqu’à la prochaine ouverture manuelle. Les restrictions d’arrière-plan propres au fabricant peuvent également intervenir ; vérifier ce fonctionnement sur le téléphone réel après association. La notification reste accessible si le système refuse le lancement de la fenêtre. Aucun contournement de l’écran de verrouillage ni autorisation de superposition n’est utilisé.

Quand le téléphone est verrouillé ou son écran éteint, la détection de présence (ou une nouvelle connexion BLE) publie une notification **« Votre vélo est prêt »**, avec le bouton **« Ouvrir E-Bike »**. Elle utilise le canal **ESP32 à proximité**, distinct de la notification silencieuse du suivi GPS. Un même identifiant évite l’empilement des alertes et les mises à jour n’émettent pas une seconde alerte. L’invitation disparaît à l’ouverture de l’application, à la déconnexion ou à la disparition de la carte. Son affichage sur l’écran verrouillé et son signal sonore restent soumis aux réglages de notifications du téléphone. Si l’application ne tourne plus, l’association système décrite ci-dessus doit être active pour recevoir la détection de présence.

## Flasher l'ESP32-S3 Zero

Ouvrir **ESP-IDF PowerShell**, puis :

```powershell
cd "C:\Users\Alban\Desktop\Dev\www\E-Bike\firmware-esp32"
idf.py set-target esp32s3
idf.py build
idf.py -p COM4 flash monitor
```

Remplacer `COM4` par le port réellement attribué à la carte. Si l'envoi ne démarre pas : maintenir **BOOT**, brancher l'USB ou appuyer sur **RESET**, relâcher **BOOT**, puis relancer la dernière commande.

Firmware OTA V1.0 : `firmware-esp32/build/ota-v1/ebike_firmware_hw_v1.bin`.
Compiler et préparer les fichiers OTA avec `./tools/build-firmware-ota.ps1` depuis ESP-IDF PowerShell.

## OTA — firmware 1.0.10 et application 0.4.32

Le nom du VAE clignote lorsqu'un firmware plus récent est disponible. La recherche
est automatique. L'installation demande dix secondes sans mouvement du capteur
de roue d'origine ; le GPS ne bloque pas l'OTA. La LED alterne orange et violet,
et trois impulsions à 2 km/h s'exécutent pendant le téléchargement et le transfert.

Un seul protocole rapide est implémenté, sans rétrocompatibilité. Depuis une
ancienne application, envoyer d'abord le firmware 1.0.9, puis installer Android
0.4.32. Le nouveau flux utilise une fenêtre de crédit de 16 Kio, des paquets jusqu'à
508 octets et les notifications de données persistées, sur un fil Bluetooth dédié.
Voir [OTA.md](OTA.md) et [les changements 1.0.9](RELEASE_ESP32_1.0.10.md).
