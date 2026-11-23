# ESP32 V1 1.0.9 — fenêtre OTA continue

Firmware 1.0.9, code OTA 8 ; Android 0.4.32, code 40.

Objectif de validation : moins de dix secondes pour le transfert confirmé du
firmware, hors téléchargement, préparation, FINISH, impulsions et redémarrage.
Le résultat sur le téléphone doit être mesuré, pas déduit des tests unitaires.

- Bluetooth Android sur un fil dédié ; seuls les changements d'interface sont
  renvoyés au fil principal. Les callbacks de transport sont traités immédiatement.
- MTU demandé 517 ; paquets jusqu'à 508 octets utiles et quatre octets d'offset.
- Fenêtre de crédit de 16 Kio, confirmations des octets réellement écrits en flash
  par notifications de 20 octets sur le contrôle OTA. Les lectures de statut ne
  servent qu'au contrôle et à la récupération d'une notification perdue.
- Tâche flash réveillée à la réception ; suppression de son attente fixe de 10 ms.
- Demande d'intervalle 7,5 ms et PHY 2M, avec trois tentatives bornées. Les
  paramètres réellement obtenus accompagnent les confirmations en mémoire.
- Temps de transfert confirmé affiché à la fin, sans journalisation persistante.

Les contrôles de roue physique, les trois impulsions finies, le SHA-256, la
validation d'image et la protection de démarrage sont conservés. Le GPS ne bloque
pas l'installation. Les changements précédents d'affichage 3D sont conservés.

Pour installer cette version sur la carte actuellement en 1.0.7, envoyer d'abord
le firmware avec l'ancien client, puis installer l'application 0.4.32. Aucun
repli vers l'ancien flux n'est ajouté au nouveau client.

La zone inactive correspondant à la taille de l’image est effacée pendant BEGIN.
Les écritures sont regroupées à 4 Kio, avec vidange du dernier bloc incomplet.
Le temps de préparation est distinct du temps de transfert confirmé.

## Validation sur le téléphone et l'ESP

Même image de 445936 octets, transférée dans la partition inactive puis validée
par FINISH (SHA-256 et contrôle d'image). L'image d'essai est ensuite annulée,
sans activation, et le firmware actif reste en 1.0.9.

| Essai | Préparation flash | Transfert confirmé | Reprises |
|---|---:|---:|---:|
| 1 | 2,036 s | 6,000 s | 0 |
| 2, après reconnexion | 2,069 s | 8,170 s | 0 |

MTU 517, PHY TX/RX 2M et intervalle réel 7,5 ms dans les deux essais.
Le passage initial par l'ancien récepteur avait pris 35,387 s pour 445904 octets.
Les mesures ne comprennent pas le téléchargement, FINISH ou le redémarrage.
Le but de moins de dix secondes est atteint dans ces deux essais ; ce n'est pas
une garantie indépendante des conditions radio.

174 tests Android sans échec, lint sans erreur, sept suites C++ et deux tests
d'empaquetage réussis. L'application normale 0.4.32 est réinstallée après les tests ;
l'activité temporaire de mesure et ses fichiers sont retirés du téléphone.
Aucun journal de trajet ni de diagnostic n'est ajouté.
