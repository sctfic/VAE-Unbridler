# ESP32 V1 1.0.6 — transfert rapide et suppression des journaux

Firmware **1.0.6, code OTA 5**, application **0.4.28, versionCode 36**.

- OTA uniquement en écriture sans réponse, avec fenêtres bornées et confirmation
  des octets réellement écrits et hachés. Une tâche distincte écrit en flash.
- Priorité Bluetooth élevée, demandes PHY 2M, intervalle 7,5–15 ms et trames
  radio de 251 octets. Les paramètres effectifs dépendent du téléphone.
- Les trois impulsions à 2 km/h s'exécutent pendant le téléchargement et le
  transfert. Leur fin est attendue avant le redémarrage si nécessaire.
- Seul le capteur de roue d'origine bloque l'installation. Le GPS et les
  impulsions de sortie n'interviennent pas dans ce contrôle.
- Suppression des journaux GPS, trajet, Bluetooth et firmware, des compteurs
  de diagnostic transmis, du panneau DEBUG et de leurs enregistreurs.
- Suppression des anciens protocoles et du transfert OTA avec réponse par paquet.
- Événements de vitesse compacts : une notification de 52 octets au lieu de
  quatre de 20 octets. Version firmware fournie par le statut OTA.
- Optimisation de taille `-Os`, logs SDK désactivés. Les assertions et contrôles
  restent actifs et silencieux. Le timer fonctionne depuis l'IRAM pendant le flash.

## Passage unique depuis la version installée

**Installer le firmware 1.0.6 par OTA avec l'application actuellement installée,
puis installer l'APK 0.4.28.** Le nouveau client n'envoie que le protocole rapide,
qui doit donc déjà être actif sur l'ESP. La nouvelle télémétrie demande aussi le
nouvel APK. Cette transition ne nécessite aucun flash USB.

Le premier transfert reste exécuté par l'ancien firmware et conserve son débit.
Les gains du nouveau récepteur concernent les mises à jour suivantes.

## Contrat de transfert

Voir [BLE_PROTOCOL.md](https://github.com/sctfic/VAE-Unbridler/blob/esp32-hw-v1-v1.0.6/BLE_PROTOCOL.md). Fenêtres de 3840 octets au maximum et
16 paquets ; tampon circulaire de 4096 octets. Confirmation par lecture du statut,
reprise depuis le dernier octet persisté après 1,5 seconde sans progression,
trois reprises par fenêtre au maximum. SHA-256 complet avant activation.
Les doublons n'écrivent pas deux fois ; les trous ou la saturation n'avancent
pas la confirmation. Une annulation attend la fin d'une écriture en cours avant
de libérer le handle. La roue, une coupure BLE et les délais annulent la session.

La progression et le débit confirmé restent affichés dans la modale, sans
journalisation. Les mesures de débit radio et des contacts pendant le flash
restent à effectuer sur le matériel ; les tests hôtes ne remplacent pas cet essai.

## Validation

- 171 tests Android sans échec ; APK et lint réussis (aucune erreur).
- Sept suites hôtes C++ et deux tests d'empaquetage réussis.
- Compilation ESP-IDF et contrôle du manifeste/SHA-256 réussis.
- Binaire : 445056 octets contre 582384 en 1.0.5, soit 137328 octets
  économisés (23.58 %). Les protections de démarrage et les contrôles restent actifs.
- Horloge et fonctions d'interruption du timer vérifiées dans l'IRAM du fichier de liens.

L'identité Android et la configuration de signature sont conservées. Ces
validations ne comprennent pas d'essai radio réel sur le téléphone et le VAE.
