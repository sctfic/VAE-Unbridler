# OTA ESP32-S3 — matériel V1.0

Le téléphone télécharge les releases publiques du dépôt `sctfic/VAE-Unbridler`,
vérifie le SHA-256, puis transmet l'image par Bluetooth. L'ESP n'utilise pas le Wi-Fi.
L'applicationId Android et la configuration de signature release sont conservés.

## Installation et utilisation

La carte actuelle possède déjà le bootloader et les partitions OTA.
Les mises à jour passent par OTA ; un flash USB nécessite une demande explicite.

Pour le passage vers le protocole unique de la 1.0.6, envoyer d'abord le firmware
avec l'application actuelle, puis installer l'APK 0.4.28. Les deux logiciels
utilisent ensuite uniquement [le protocole courant](BLE_PROTOCOL.md).
Le premier transfert garde le débit de l'ancien récepteur.

1. La recherche GitHub est automatique à la connexion, puis toutes les heures.
2. Une nouvelle version fait clignoter le nom du VAE. Toucher ce nom pour ouvrir
   la modale ; l'association Android est proposée seulement si aucun ESP n'est détecté.
3. Immobiliser la roue pendant dix secondes, puis lancer l'installation.
4. Garder l'alimentation et le téléphone à proximité jusqu'à la fin. La LED
   alterne orange et violet. Trois impulsions de sortie à 2 km/h maintiennent
   l'alimentation pendant le téléchargement et le transfert.
5. La modale affiche les octets confirmés, le débit, la vérification et le
   nouveau démarrage, sans exposer les partitions.

Le service poursuit l'opération en arrière-plan et à la rotation. Les réglages
et l'association sont bloqués pendant l'installation. Un arrêt du processus
avant activation interrompt le transfert ; une nouvelle tentative repart de zéro.

## Partitions et contrôles

| Partition | Emplacement | Taille |
|---|---:|---:|
| NVS | `0x9000` | 24 Kio |
| Métadonnées OTA | `0xf000` | 8 Kio |
| PHY | `0x11000` | 4 Kio |
| `ota_0` | `0x20000` | 1920 Kio |
| `ota_1` | `0x200000` | 1920 Kio |

Seule la partition inactive est écrite. L'activation suit la réception complète,
le SHA-256, la validation ESP-IDF et la vérification du nom de projet.
Un front brut du capteur de roue annule la session, même si la roue s'arrête
ensuite. Le GPS et les impulsions de sortie n'interviennent pas dans ce contrôle.
Une coupure BLE avant activation ou trente secondes d'inactivité annulent aussi
la session. Turbo est désactivé au début et reste désactivé après un redémarrage.

Le firmware confirme son démarrage après dix secondes de disponibilité BLE et
de fonctionnement de la tâche roue. Une tâche indépendante impose le retour à
l'image précédente si aucune confirmation n'arrive après 45 secondes. Le
bootloader conserve également son mécanisme de repli après un échec de démarrage.
Ces contrôles sont conservés : ce sont les protections de l'installation.

Le téléchargement est limité aux hôtes HTTPS GitHub, à la capacité de la partition
et au dépôt attendu. Aucun jeton GitHub n'est embarqué. Le SHA-256 assure
l'intégrité ; il ne constitue pas une signature indépendante de l'éditeur.

## Préparer et publier

L'application lit les cent dernières releases **publiées et stables** dont le
tag suit `esp32-hw-v1-vX.Y.Z`. Les codes doivent strictement augmenter.
Augmenter `firmware-esp32/version.txt` et `EBIKE_FIRMWARE_VERSION_CODE` dans
`main/include/firmware_version.hpp`. Avant chaque commit, augmenter aussi les
deux versions Android selon [AGENTS.md](AGENTS.md).

Depuis ESP-IDF PowerShell, dans une copie propre du commit à publier :

```powershell
# Compilation et empaquetage, sans installation.
./tools/build-firmware-ota.ps1
# Préparation d'un brouillon, après autorisation de publication.
./tools/publish-firmware-release.ps1 -Notes RELEASE_ESP32_1.0.6.md
```

Le script vérifie puis prépare `ebike-esp32s3-hw-v1.bin` (application seule) et
`ebike-esp32s3-hw-v1-ota.json` (version, code, taille, SHA-256, matériel V1.0,
protocole OTA 1 et URL). Il refuse un code non croissant et l'écrasement d'une
release publiée. L'option `-Publish` publie immédiatement quand cela est autorisé.

La compilation force le profil de taille et de silence même pour une configuration
SDK déjà créée. Les assertions et ESP_ERROR_CHECK restent actifs, le timer et
ses fonctions d'interruption restent en IRAM pendant les écritures flash.

## Vérification

```powershell
gradle -p android-app :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./tools/test-firmware-host.ps1
python -m unittest discover -s tools -p test_package_firmware.py -v
```

Les tests couvrent les fenêtres, reprises, limites mémoire, doublons, erreurs
d'ordre, SHA-256, timeout, mouvement et annulation pendant une écriture.
Les tests de timer vérifient la cadence, les contacts de 2 ms et les trois
impulsions finies. Le débit radio, le maintien de l'alimentation et les contacts
pendant une vraie écriture flash restent à vérifier sur le matériel.
