# ESP32 V1 1.0.4 — test de mise à jour OTA

Cette version permet de tester la mise à jour Bluetooth depuis le firmware
**1.0.3 (code OTA 2)** installé par USB. Elle conserve le fonctionnement de la
1.0.3 ; seules l’identité du firmware et sa version OTA changent :
**1.0.4, code OTA 3**.

- Matériel : ESP32-S3, carte V1.0 avec sortie moteur open-drain.
- Application compatible : E-BikeCockpit 0.4.24 (versionCode 32).
- Protocole OTA 1 et événements BLE v2 ; réglages NVS conservés.
- Le binaire est une image d’application pour la partition OTA inactive,
  accompagnée du manifeste de taille et SHA-256. Aucun bootloader ni table
  de partitions n’est envoyé par cette mise à jour Bluetooth.

## Tester depuis l’application

1. Immobiliser le VAE pendant au moins dix secondes et maintenir son alimentation.
2. Ouvrir Setting, puis la section firmware ESP32 et lancer la recherche de mise
   à jour GitHub. La version proposée doit être 1.0.4, version actuelle 1.0.3.
3. Lancer l’installation et attendre le transfert, la vérification, le redémarrage
   puis la confirmation de démarrage du nouveau firmware.
4. Vérifier que Setting indique 1.0.4, que le Bluetooth se reconnecte et que le
   réglage de roue est conservé. En cas d’échec de démarrage, le firmware
   précédent reste disponible dans l’autre partition pour le retour automatique.

## Validation

Compilation ESP-IDF et contrôles de l’image OTA avant publication. Le comportement
de la 1.0.3 a été vérifié au démarrage USB et sur la connexion BLE ; les essais
physiques de vitesse et de continuité sur banc/VAE restent à effectuer.
Cette publication ne déclenche aucune installation sur les appareils.
