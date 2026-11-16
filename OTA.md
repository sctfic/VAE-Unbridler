# OTA ESP32-S3 — application 0.4.23, matériel V1.0

Le téléphone utilise Internet pour télécharger les releases publiques du dépôt
`sctfic/VAE-Unbridler`. Il vérifie leur SHA-256 puis transmet le binaire par BLE.
L’ESP32 n’a pas besoin de Wi-Fi. L’identifiant Android et la signature release
existante sont conservés.

## Installation et utilisation

1. Installer l’application 0.4.23 ou ultérieure.
2. Sur une carte équipée du firmware précédent, effectuer une première
   installation **USB complète** avec ESP-IDF 5.3.2 : depuis la racine du dépôt,
   dans ESP-IDF PowerShell, lancer
   `./tools/build-firmware-ota.ps1 -Flash -Port COM4` (adapter le port).
   La nouvelle table nécessite au moins 4 Mio de flash. Ne pas envoyer uniquement
   le fichier d’application à l’ancien emplacement `0x10000` : le nouvel
   emplacement initial est `0x20000`. Ne pas effacer la NVS : son emplacement
   `0x9000` et sa taille de 24 Kio sont conservés.
   Le script utilise une configuration dédiée `build/ota-v1-sdkconfig` et le
   dossier `build/ota-v1` pour activer les partitions, la flash 4MB et le repli,
   même si l’ancien `sdkconfig` décrit une application unique et une flash 2MB.
   La taille physique de flash doit être d’au moins 4 Mio.
   Les réglages de l’ancien build restent disponibles.
3. Connecter l’ESP32 dans l’application. La première validation de démarrage
   prend au moins dix secondes. La recherche GitHub se fait à la connexion
   puis toutes les heures tant que l’ESP32 reste connecté, ou avec
   **Rechercher une mise à jour**.
4. Une release compatible plus récente fait clignoter **nom du VAE · MAJ**, en haut
   à gauche, en portrait comme en paysage. Toucher ce nom pour voir les versions.
5. Garder le VAE immobile pendant dix secondes, puis toucher **Installer**.
   L’application affiche téléchargement, préparation, transfert/écriture avec
   octets et pourcentage, vérification, bascule/redémarrage et confirmation.
   **Ne pas utiliser le VAE, faire tourner sa roue ou couper son alimentation.**
   Garder le téléphone à proximité jusqu’à la confirmation finale.

Le dialogue reste ouvert pendant l’installation. Le service Android poursuit
l’opération en arrière-plan et lors d’une rotation d’écran ; la notification
persistante rappelle de ne pas utiliser le VAE. Les réglages et l’association
Android sont bloqués pendant l’installation. Un arrêt du service ou du processus
avant activation interrompt le transfert ; une nouvelle tentative repart de zéro.

## Partitions et contrôles

| Partition | Emplacement | Taille |
|---|---:|---:|
| NVS | `0x9000` | 24 Kio |
| Métadonnées OTA | `0xf000` | 8 Kio |
| PHY | `0x11000` | 4 Kio |
| `ota_0` | `0x20000` | 1 920 Kio |
| `ota_1` | `0x200000` | 1 920 Kio |

Le firmware utilise `esp_ota_get_next_update_partition` pour écrire exclusivement
dans la partition inactive : A → B, puis B → A. Le firmware courant n’est pas
effacé pendant le transfert. Le bootloader sélectionne la nouvelle partition
uniquement après réception de tous les octets, contrôle SHA-256, validation
ESP-IDF de l’image et vérification du nom de projet.

L’immobilité est contrôlée sur l’ESP32 à partir de **chaque front brut du capteur**,
y compris la première impulsion sans vitesse calculable. Il faut dix secondes
sans front ni mouvement mesuré. Chaque paquet, la validation, l’activation et
la boucle de suivi recontrôlent le mouvement. Un front pendant une session annule
cette session, même si la roue s’arrête ensuite. Une coupure BLE avant activation
ou trente secondes sans données annule également la session. Le mode Turbo est
désactivé au début du transfert et reste désactivé après interruption/redémarrage.
L’application bloque aussi l’installation si son GPS signale au moins 1 km/h.
Un capteur de roue débranché ne permet pas de certifier physiquement l’immobilité.

Après activation, l’application se reconnecte à **la même adresse BLE** et vérifie
la version, son code et la partition attendue. Le firmware confirme son démarrage
après dix secondes de disponibilité BLE et de fonctionnement de la tâche roue.
Une tâche indépendante impose un retour au firmware précédent si la nouvelle
image reste sans confirmation après 45 secondes. Un crash ou une coupure avant
confirmation provoque également le repli au prochain démarrage. Aucun succès
n’est affiché sur la seule base d’un transfert arrivé à 100 %.

Le téléchargement est limité aux hôtes HTTPS GitHub, à la taille de la partition
et à la release du dépôt attendu. Le manifeste et le binaire sont publics ; aucun
jeton GitHub n’est embarqué. Le SHA-256 assure l’intégrité du fichier récupéré via
HTTPS ; il ne constitue pas une signature indépendante de l’éditeur. Le canal
BLE conserve le modèle d’accès du protocole existant.

Référence des API de bascule et de repli :
[ESP-IDF 5.3 OTA](https://docs.espressif.com/projects/esp-idf/en/release-v5.3/esp32s3/api-reference/system/ota.html).

## Préparer et publier un firmware

Les releases Android et ESP32 ont des noms différents. L’application recherche
les releases **publiées et stables** dont le tag est `esp32-hw-v1-vX.Y.Z`, parmi les cent
dernières releases. Les brouillons et préversions sont ignorés. La première
release ESP32 trouvée dans l’ordre GitHub est utilisée ; les codes doivent
strictement augmenter et la publication ne doit pas être antidatée.

Pour chaque firmware suivant, augmenter `firmware-esp32/version.txt` et
`EBIKE_FIRMWARE_VERSION_CODE` dans `main/include/firmware_version.hpp`.
Le premier firmware OTA V1.0 est `1.0.2`, code 1. Une carte déjà en code 1 n’installera
que le code 2 ou un code supérieur.

Depuis une copie propre du commit à publier :

```powershell
# Dans ESP-IDF PowerShell, depuis la racine du dépôt
# Compile et prépare les deux fichiers OTA, sans flasher.
./tools/build-firmware-ota.ps1
./tools/publish-firmware-release.ps1 -Notes RELEASE_FIRMWARE_V1_1.0.2.md
```

Le script prépare un **brouillon** GitHub avec :

- `ebike-esp32s3-hw-v1.bin` : uniquement l’application, jamais un binaire flash fusionné ;
- `ebike-esp32s3-hw-v1-ota.json` : version/code lus dans l’image compilée, taille,
  SHA-256, cible `ebike-esp32s3-hw-v1`, protocole OTA 1 et URL de téléchargement.

Le préparateur vérifie le type de puce, le projet, les segments, le checksum et
le SHA-256 interne ESP-IDF. Le script de publication vérifie à nouveau le fichier
et refuse un code non croissant ou l’écrasement d’une release publiée. Après
validation sur banc, publier le brouillon sur GitHub. L’option `-Publish` permet
de publier immédiatement lors de l’exécution explicite du script. Ne pas relancer
sur un brouillon contenant déjà les fichiers : l’inspecter d’abord.

## Vérifications

```powershell
# Application Android
cd android-app
gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# Depuis la racine : tests C++ Windows (Visual C++ Build Tools) et préparateur
./tools/test-firmware-host.ps1
python -m unittest discover -s tools -p test_package_firmware.py -v
```

Les tests hôtes exécutent le service OTA réel avec flash/horloge/BLE simulées
et SHA-256 Windows. Ils couvrent transfert valide, A → B et B → A, premier boot
USB, confirmation, mouvement avant/pendant/après activation, déconnexion,
paquets incomplets ou mal ordonnés, timeout, hash incorrect et erreur flash.
Ils ne simulent pas le bootloader ni la radio physique.

Avant diffusion, vérifier sur un banc sans utilisation du VAE : deux mises à jour
successives avec augmentation du code ; mouvement dès la première impulsion ;
coupure BLE et alimentation pendant transfert ; redémarrage volontaire avant
confirmation pour observer le repli ; absence de confirmation BLE/tâche roue ;
reconnexion du téléphone ; maintien des réglages NVS et du fonctionnement du
contact moteur. Le firmware ne doit pas être considéré validé sur matériel par
la seule réussite de la compilation et des tests hôtes.

## Compatibilité matérielle V1.0

Cette variante utilise la sortie moteur GPIO6 **à drain ouvert** et conserve le
filtre de période de roue et les diagnostics de la 0.4.23. Le projet ESP-IDF est
`ebike_firmware_hw_v1` et produit `build/ebike_firmware_hw_v1.bin` (1.0.2).
Les releases utilisent `esp32-hw-v1-vX.Y.Z`, le manifeste contient
`hardware_revision: "V1.0"` et la cible `ebike-esp32s3-hw-v1`.
Le statut BLE identifie la révision `0x0100`. La carte refuse en OTA une image
d’un autre projet ; l’application ignore les canaux PhotoMOS et V3.
L’application Android garde explicitement versionName 0.4.23 et versionCode 31.
Les UUID diagnostics `a6f50003` et version `a6f50004` sont conservés ; l’OTA
utilise les nouveaux UUID `a6f50005` (contrôle) et `a6f50006` (données).
L’installation est liée à l’adresse Bluetooth du profil connecté.
