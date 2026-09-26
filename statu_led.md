# États de la LED RGB de l’ESP32-S3 Zero

LED WS2812 intégrée sur **GPIO 21**, ordre des couleurs GRB. Luminosité volontairement faible (canaux limités à 24/255).

| Couleur | Affichage | Signification |
|---|---|---|
| Violet | Fixe, au moins 1 seconde | Démarrage et initialisation. Reste violet tant que la première annonce Bluetooth n’a pas démarré. |
| Jaune | 0,5 s allumé / 0,5 s éteint | Prêt, roue arrêtée, en attente d’une connexion Bluetooth. |
| Bleu | Fixe | Bluetooth connecté, roue arrêtée. Indique la connexion BLE, pas la réception GPS du téléphone. |
| Vert | Fixe sans Bluetooth ; alterné avec bleu si connecté | Roue en mouvement, transmission des impulsions au rythme mesuré (jusqu’au seuil configuré, 22,2 km/h par défaut). |
| Orange | Fixe sans Bluetooth ; alterné avec bleu si connecté | Roue en mouvement, génération des intervalles de vitesse simulée au-dessus du seuil. |
| Rouge | 0,5 s allumé / 0,5 s éteint | Échec détecté d’initialisation NVS ou Bluetooth, de sélection de l’adresse BLE, de configuration ou de démarrage de l’annonce Bluetooth. |

En mouvement avec Bluetooth connecté, l’alternance est **0,5 s bleu / 0,5 s vert ou orange**. Elle permet de lire simultanément la connexion et le mode des impulsions.

Priorité : **erreur rouge > démarrage violet > état de connexion et mouvement**. Le rouge reste mémorisé jusqu’au redémarrage. Il ne signifie pas nécessairement que les impulsions sont arrêtées. L’arrêt de la roue suit le délai de détection du firmware, pas le dernier front instantanément.

La LED est pilotée par le périphérique matériel RMT, depuis une tâche de faible priorité sur le cœur 0. La capture de roue et le générateur d’impulsions restent indépendants ; aucune transmission LED n’est effectuée dans leurs interruptions.

Limites : le rouge ne couvre pas tous les plantages, watchdogs ou coupures d’alimentation. Si le pilote de la LED échoue, seule une erreur dans les journaux est possible. Une LED éteinte n’est donc pas un diagnostic certain. Les couleurs doivent être confirmées visuellement sur la carte, notamment à travers la résine.
