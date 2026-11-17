# ESP32 V1 1.0.5 — préparation anti-veille des mises à jour

Firmware **1.0.5, code OTA 4**, application **0.4.26, versionCode 34**.

- Pendant l’OTA, la LED alterne orange et violet toutes les 0,5 seconde.
- Au lancement depuis l’application compatible, le firmware émet exactement
  trois impulsions moteur de 2 ms, espacées comme à 2 km/h selon le périmètre
  de roue configuré. Avec 2361 mm : 4 249 800 µs entre fronts, puis arrêt.
  Aucun renouvellement périodique des impulsions n’est ajouté.
- Le téléphone attend la fin des trois impulsions avant de télécharger le
  firmware. La préparation est maintenue pendant le téléchargement sans répéter
  les impulsions. Le signal vise à retarder la veille du contrôleur ; son effet
  sur l’alimentation doit être confirmé sur le VAE.
- Un front du capteur roue annule le train d’impulsions, même s’il est ensuite
  rejeté comme parasite. Déconnexion, erreur, annulation et délai de 30 secondes
  sans commande arrêtent aussi la préparation. Un contact déjà fermé se termine
  proprement. Les impulsions de sortie ne sont pas des mesures de roue/GPS.
- La modale Android masque les partitions, recherche automatiquement les mises
  à jour et propose l’association uniquement quand aucun ESP32 n’est détecté.
  Le dénivelé est affiché sans signe, avec les flèches montée/descente.

## Compatibilité

La mise à jour depuis 1.0.3/1.0.4 reste possible. La LED OTA et les impulsions
nécessitent toutefois que la 1.0.5 soit **déjà installée** : elles seront donc
actives lors des mises à jour suivantes. Un ancien firmware ne peut pas exécuter
ces nouvelles commandes. L’application n’envoie pas de commande inconnue aux
anciennes versions. Les réglages de roue et la signature Android sont conservés.

## Extension OTA 1

Le paquet de statut reste de 48 octets. Le bit 3 des flags annonce la préparation
anti-veille ; le bit 4 signale la fin des trois impulsions. La commande `05`
(1 octet) passe à l’état 5 (préparation) après validation de l’immobilité et
du démarrage. Les doublons renouvellent le délai de session sans ajouter de
front. L’application envoie ce rappel toutes les cinq secondes pendant le
seul téléchargement. BEGIN est accepté ensuite, sur la même référence de
roue immobile ; aucune écriture flash n’est commencée avant la fin du train.
Le chemin BEGIN historique sans préparation reste compatible avec les anciens
clients, sans train anti-veille. ABORT et les délais précédents restent actifs.

Les tests hôtes exécutent le vrai générateur avec timer simulé : trois fronts
à 2 km/h pour plusieurs périmètres, contact de 2 ms, annulation, pas de
quatrième front ni de fausse mesure roue. Les tests OTA couvrent aussi les
rappels, les pertes BLE, le mouvement et les délais de préparation.
Les essais électriques et anti-veille sur le contrôleur restent à effectuer.
