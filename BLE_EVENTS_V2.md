# Acquisition et événements BLE v2 — décision avant implémentation

## Validation et confirmation
La référence physique est la dernière impulsion roue acceptée. Les rejets (>99 km/h,
accélération positive >15 km/h/s sur le temps depuis cette référence, intervalle <30 ms)
ne la déplacent jamais. Le freinage est accepté sans limite. Arrêt après
max(1,5 s, 3 × dernier intervalle), acquisition initiale limitée à 15 s.

Turbo seul : activer au-dessus de 20 km/h et désactiver sous 18 km/h. La mesure P
attend la prochaine mesure Q physiquement acceptée. Confirmer P seulement si
abs(vQ-vP) <= 15 km/h/s × (tQ-tP). Cette vérification supplémentaire concerne la
commande moteur, pas l’acceptation des freinages roue. Sinon abandonner P et
attendre Q. Un front physiquement rejeté annule P ; il ne confirme rien.
Après confirmation, appliquer P et garder Q en attente (une mesure d’avance).
Sortie sous 18 ou passage Standard : appliquer directement la dernière mesure
roue valide, sans arrêter/redémarrer le générateur. Entrée Turbo : conserver la
cadence existante jusqu’à confirmation. Démarrage : acquérir deux fronts ; en
Turbo rapide attendre la troisième impulsion. Arrêt/reprise effacent l’attente.
La formule Turbo existante reste inchangée. Aucun lissage moteur sur 2 s.

## Continuité
Les échéances sont calculées depuis le dernier front moteur réellement émis,
sous le même verrou que l’ISR du timer. Une commande inchangée ne réarme pas le
timer. Si une échéance est déjà passée, programmer au plus un front maintenant
(avec une marge timer de 20 µs). L’intervalle depuis le dernier front réel est
alors déjà au moins l’intervalle commandé ; le suivant repart de ce nouveau
front réel. Ne jamais rejouer les échéances manquées ni ajouter un intervalle
complet de trou lors d’une transition. Un arrêt laisse terminer le contact de
2 ms. Une garde dans l’ISR empêche tout nouveau front au-delà du délai d’arrêt,
y compris si la tâche roue est retardée. Les mesures acceptées en attente
renouvellent cette garde ; les fronts rejetés ne la renouvellent pas.

## Transport
La configuration et OTA restent v1. La lecture de version firmware reste sur
0004. La caractéristique historique 0001 ne notifie plus : sa lecture retourne
une version 2 explicitement incompatible avec les décodeurs v1. Les applications
v1 doivent être mises à jour ; aucune vitesse périodique de substitution.
La nouvelle caractéristique 0007 utilise toujours des notifications de 20 octets,
compatibles ATT MTU 23 (même si un MTU supérieur est négocié).

Chaque événement se compose de quatre fragments ordonnés de 20 octets :
version u8=2, type/index u8=(type<<4)|index (index 0..3), séquence u16 LE,
16 octets de charge. Types : 1 roue acceptée, 2 état, 3 présence (5 s).
Un seul événement roue par mesure physique acceptée, même en attente moteur.
Un état initial est émis après abonnement ; arrêt/mode/configuration et défauts
sont séparés des mesures. La file bornée perd les nouveaux événements en cas
de saturation et compte ces pertes ; aucune attente Bluetooth sur le cœur RT.

Charge reconstituée de 64 octets, little endian :

| Offset | Type | Valeur |
|---|---|---|
|0|u64|session aléatoire de démarrage ESP32|
|8|u64|instant de l’événement, µs monotones esp_timer|
|16|u64|dernière impulsion roue acceptée, µs|
|24|u64|dernier front moteur réellement émis, µs esp_timer|
|32|u32|intervalle roue accepté, µs|
|36|u32|intervalle moteur commandé, µs|
|40|u32|révolutions acceptées|
|44|u32|impulsions moteur émises|
|48|u32|fronts rejetés|
|52|u32|fronts perdus par la file ISR|
|56|u16|circonférence, mm|
|58|u8|bits : mouvement, simulation, Turbo, mode supporté, instant moteur valide|
|59|u8|confirmation : 0 inactive, 1 attente, 2 confirmation + suivante en attente|
|60|u32|événements/fragments BLE perdus|

Séquence modulo 65536 pour tous les événements, comparaison par demi-plage.
Compteurs u32 modulo 2^32, remis à zéro au démarrage. Instants u64 (pas de wrap
pratique). Un instant moteur absent est signalé par bit 4 à zéro. Une session
change au redémarrage ; les fragments incomplets ne sont jamais des mesures.

## Horloges et affichage
Android utilise elapsedRealtime, jamais l’heure civile ou l’espacement des
notifications pour calculer les vitesses. Le premier état/présence établit la
correspondance réception - instant ESP32. Le minimum observé de cette différence
réduit l’erreur de transport ; une latence unidirectionnelle initiale reste
indéterminable sans aller-retour. Une mesure reçue avant cet état est ignorée.
Reconnexion/session réinitialisent correspondance, séquences et fenêtres ; une
ancienne session déjà remplacée ne peut pas revenir. Doublons/désordre ignorés,
pertes comptées ; fragments incomplets expirent après 1 s. Communication perdue
après 12 s sans événement complet récent (présence toutes les 5 s).

L’affichage est rafraîchi toutes les 250 ms. Les fenêtres de 2 s ne reçoivent
que de nouvelles mesures horodatées. Après l’intervalle attendu, la roue est
bornée par circonférence × 3,6 / âge depuis l’impulsion, puis zéro au délai
ESP32. La vitesse moteur représente l’intervalle commandé, avec preuve d’au moins un
front réellement émis ; ce front sert également au diagnostic de phase. La
cadence commandée est conservée jusqu’au délai d’arrêt roue, comme le firmware.
L’absence de notification moteur ne signifie pas absence de fronts moteur et
ne doit donc pas provoquer de décroissance artificielle.
L’état arrêt force zéro ; communication perdue produit une valeur absente.
Le GPS garde sa validité propre et sa priorité, sauf zéro GPS contredit par
une roue en mouvement. Aucune décroissance roue n’est appliquée au GPS.

Les essais automatisés sont logiciels ; vérifier physiquement au générateur
et à l’oscilloscope les transitions, les seuils, le contact de 2 ms et les délais,
puis sur le VAE. Aucun flash ni publication automatique.

Les échantillons sont ajoutés dans le service à chaque événement, même si plusieurs
impulsions arrivent entre deux rafraîchissements UI de 250 ms. Les traces DEBUG
`wheel` donnent intervalle brut/candidat, rejet/acceptation, confirmation,
commande et compteur/instant du front réellement émis, sans journaliser dans
l’ISR. Activer le niveau DEBUG à la compilation pour ces traces de banc.

## Validation logicielle et essais restant sur le VAE

Base : `codex/hardware-v1-speed-fix`, commit `a788785`.
Versions : Android 0.4.24 / versionCode 32 ; firmware V1 1.0.3 / code OTA 2.

- `:app:testDebugUnitTest` : 166 tests, aucun échec ni test ignoré.
- `:app:assembleDebug` et `:app:lintDebug` : réussis.
- `tools/test-firmware-host.ps1` : six suites réussies (modèle de vitesse,
  validation roue, confirmation Turbo, vrai générateur sur timer simulé,
  politique OTA, service OTA).
- `tools/test_package_firmware.py` : deux tests réussis.
- `tools/build-firmware-ota.ps1` sans option Flash : compilation ESP-IDF 5.3
  et vérification du binaire/manifeste OTA, taille compatible avec la partition.
- Différences Git sans erreur d’espacement ; fichiers source modifiés en UTF-8.

Le timer simulé exécute `pulse_generator.cpp` et vérifie les fronts réels simulés,
le contact de 2 ms, les changements pendant un contact, les alarmes retardées,
l’absence de rafale, la garde d’arrêt autonome et la reprise. Il ne reproduit
pas la charge d’interruptions ni les caractéristiques électriques du matériel.

Sur banc, enregistrer simultanément GPIO5 et GPIO6 à l’oscilloscope/analyseur :
1. Démarrage lent, freinage fort, arrêt, reprise, parasite et rafale de parasites.
2. Montée/descente 18 et 20 km/h, bascule Standard/Turbo, contact de 2 ms,
   espacement des fronts et maintien jusqu’au seul délai d’arrêt valide.
3. BLE déconnecté, MTU 23, téléphone éloigné, reprise BLE et redémarrage ESP32.
4. Comparer GPS valide, GPS zéro avec roue mobile, estimation puis zéro roue,
   version dans Setting et circonférence propre à chaque vélo dès le démarrage.
5. Après ces mesures, valider le comportement avec le contrôleur du VAE.

Installation USB autorisée et effectuée le 17 novembre 2026 : Android 0.4.24
sur OnePlus 8T et firmware 1.0.3 sur ESP32-S3. Migration de l’ancien firmware
1.0.1 sans OTA vers les partitions OTA après sauvegarde locale complète dans
`firmware-esp32/build/` (ignoré par Git). La partition NVS conserve son adresse
et sa taille ; aucune zone NVS n’est effacée par le flash. L’APK est installé
avec la même signature, sans désinstallation ni effacement des données.
Le smartphone lit 1.0.3 et reçoit les présences v2 pendant plus de 50 secondes
sans changement de session ; la circonférence remontée reste 2361 mm.
La sortie du mode téléchargement USB a nécessité un redémarrage watchdog.
Les essais dynamiques sur banc et sur VAE restent à faire.
Aucun commit ou push effectué. ApplicationId et configuration existante de
signature release conservés ; aucun secret n’est ajouté aux sources.
