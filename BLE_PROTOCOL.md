# Protocole Bluetooth courant

Firmware matériel V1 **1.0.10**, application **0.4.32**. Un seul protocole est
implémenté : aucun décodeur ni transfert pour les anciennes versions.
Tous les nombres multioctets sont en little-endian.

Service : `a6f50000-9b8a-4d9f-a0da-9e9d873d1101`.
Android demande un MTU de 517 et exige au moins 55. Les UUID ci-dessous
partagent le suffixe `-9b8a-4d9f-a0da-9e9d873d1101`.

| Caractéristique | UUID initial | Accès |
|---|---|---|
| Événements roue/état | `a6f50007` | Notification |
| Configuration | `a6f50002` | Lecture, écriture avec réponse |
| Contrôle et statut OTA | `a6f50005` | Lecture, écriture avec réponse, notification |
| Données OTA | `a6f50006` | Écriture sans réponse |

Les anciennes caractéristiques de télémétrie, diagnostic brut et lecture de
version séparée sont supprimées. La version est fournie par le statut OTA.
Aucun journal ni compteur de diagnostic n'est enregistré ou transféré.

## Événement de vitesse : une notification de 52 octets

| Offset | Type | Valeur |
|---:|---|---|
| 0 | u8 | Format 3 |
| 1 | u8 | 1 : roue acceptée ; 2 : état ; 3 : présence |
| 2 | u16 | Séquence, modulo 65536 |
| 4 | u64 | Session aléatoire au démarrage |
| 12 | u64 | Heure ESP de l'événement, µs |
| 20 | u64 | Heure de la dernière mesure roue acceptée, µs |
| 28 | u64 | Heure du dernier front moteur réellement émis, µs |
| 36 | u32 | Période roue retenue, µs |
| 40 | u32 | Période moteur commandée, µs |
| 44 | u32 | Tours de roue acceptés, pour le calibrage |
| 48 | u16 | Circonférence, mm |
| 50 | u8 | Bits 0 : mouvement ; 1 : simulation ; 2 : Turbo ; 3 : heure moteur valide |
| 51 | u8 | Confirmation Turbo : 0 aucune, 1 en attente, 2 confirmée |

Les vitesses sont calculées depuis les périodes et la circonférence, jamais
depuis l'espacement de réception. La séquence et la session rejettent doublons,
désordre et anciens paquets. Un état actuel ouvre chaque connexion ; les anciennes
mesures en attente ne sont pas rejouées. Une présence est envoyée toutes les cinq
secondes. L'affichage Android conserve le lissage indépendant sur deux secondes,
la limite physique entre deux tours et l'estimation d'arrêt.

## Configuration : 8 octets

`u8 format=1 ; u8 mode ; u16 circonférence_mm ; u16 seuil_centi_kmh ; u16 réservé=0`.
Le mode vaut 0 pour conserver le mode courant, 2 pour Standard, 3 pour Turbo.
Ces trois opérations sont utilisées par les réglages actuels. Circonférence
acceptée : 1000–4000 mm. Les écritures sont refusées pendant une mise à jour.

## Statut OTA : 48 octets

`u8 format=1 ; u8 état ; u8 erreur ; u8 flags ; u32 received ; u32 taille ;
u32 code_version ; u8 partition_active ; u8 partition_cible ; u16 matériel=0x0100 ;
u32 capacité ; char version[24]`.

États : 0 repos, 1 réception, 2 vérifié, 3 redémarrage, 4 erreur, 5 préparation.
Flags : bit 0 partition disponible, 1 roue immobile, 2 démarrage confirmé,
3 impulsions disponibles, 4 impulsions terminées, 5 transfert rapide disponible.
Les disponibilités sont fixes pour ce firmware ; Android ne négocie aucun repli.
Les partitions servent aux vérifications internes et ne sont pas affichées.

## OTA : transfert rapide unique

- `05` : préparation, démarre trois impulsions de sortie à 2 km/h. Une répétition
  entretient le délai de préparation sans ajouter d'impulsion.
- `06 + u32 taille + SHA-256[32] + u32 code_version` : début du transfert.
  Le téléchargement et le transfert peuvent chevaucher les trois impulsions.
- Données : `u32 offset + octets[1..508]`, sans réponse ATT individuelle.
- `02` : terminer et vérifier le SHA-256 et l'image.
- `04` : activer après la fin des trois impulsions, puis redémarrer.
- `03` : annuler.

La commande BEGIN `06` efface la zone inactive correspondant à la taille annoncée
avant la phase de transfert. Les impulsions peuvent se poursuivre pendant cette
préparation. L'ancienne commande `01` est refusée.

Le crédit maximal est de 16384 octets non encore confirmés, incluant les octets
en file et ceux en cours d'écriture. Le tampon circulaire fait 16 Kio. La tâche
flash est réveillée par notification de tâche ; elle regroupe les données en
blocs de 4096 octets et vide le dernier bloc incomplet à la fin de l'image.

Notifications sur le contrôle OTA, 20 octets :

`u8 format=1 ; u8 état ; u8 erreur ; u8 réservé=0 ; u32 code_cible ;
u32 received ; u32 taille ; u16 intervalle_1.25ms ; u8 phy_tx ; u8 phy_rx`.

`received` confirme exclusivement les octets écrits en flash et hachés. Le code
cible et la taille permettent au client d'ignorer les anciennes notifications.
Le client alimente la fenêtre depuis un fil Bluetooth dédié, en respectant les
callbacks d'écriture Android. Il n'envoie pas de petit paquet uniquement pour
remplir les derniers octets de crédit. Les confirmations déjà reçues accordent
immédiatement le crédit suivant, sans lecture de statut entre blocs.

Après 1,5 seconde sans notification utile, une lecture de statut récupère un ACK
perdu. En l'absence de progression confirmée, reprise depuis le dernier octet
persisté, avec trois reprises consécutives au maximum. Les doublons sont ignorés ;
les trous et la saturation n'avancent pas la confirmation. Un offset hors image
annule l'installation. FINISH exige une file vide et toutes les écritures terminées.

Android demande la priorité élevée et le PHY 2M. L'ESP demande 7,5 ms, DLE 251 et
PHY 2M ; trois tentatives sont bornées et espacées d'au moins une seconde. Les
paramètres réellement obtenus accompagnent les ACK. Ils ne sont pas journalisés.
La priorité normale revient en fin d'opération. Les durées de préparation,
transfert confirmé et validation/redémarrage restent distinctes.

Seul un front physique du capteur de roue bloque ou annule la session.
L'immobilité préalable dure dix secondes. Le GPS et les impulsions de sortie
n'interviennent pas dans ce contrôle. Une déconnexion avant activation ou trente
secondes d'inactivité annulent également le transfert. Le contrôle de démarrage
et le repli automatique restent actifs. Voir [OTA.md](OTA.md).
