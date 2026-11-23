# ESP32 V1 1.0.10 — test du transfert OTA accéléré

Firmware 1.0.10, code OTA 9. Utiliser Android 0.4.33.
Cette version est identique en fonctionnement à la 1.0.9 testée sur la carte ;
seuls la version et le code OTA augmentent pour proposer une nouvelle mise à jour.

Le transfert utilise un thread Bluetooth Android dédié, des paquets jusqu'à
508 octets, une fenêtre continue de 16 Kio et des confirmations des octets
réellement écrits en flash. La zone inactive est effacée pendant la préparation,
puis les écritures sont regroupées par blocs de 4 Kio.

Sur la 1.0.9, deux essais réels de 445936 octets ont pris 6,000 s et 8,170 s,
sans reprise, avec SHA-256 et image validés. La préparation flash prenait environ
deux secondes supplémentaires ; téléchargement, FINISH et redémarrage sont exclus
du temps de transfert. Ces mesures ne garantissent pas le débit dans toute
condition radio.

Les contrôles de roue d'origine, les trois impulsions anti-veille et la protection
de démarrage sont conservés. Le GPS ne bloque pas l'installation. Aucun journal
persistant n'est ajouté.

Validation logicielle du flux : 174 tests Android, lint sans erreur, sept suites
C++ et deux tests d'empaquetage réussis. La 1.0.10 est recompilée et son manifeste
est vérifié avant publication. Elle est destinée au nouveau test OTA depuis
la 1.0.9 installée, sans installation automatique.

Android 0.4.33 corrige la recherche OTA : les releases stables de firmware sont
triées par date de publication, même si GitHub ne renvoie pas la plus récente
en premier. Le protocole de transfert reste identique à celui de la 0.4.32.
