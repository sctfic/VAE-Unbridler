# E-BikeCockpit 0.4.4 — versionCode 12

- Suppression du bouton « Retour au direct ».
- Le tableau de bord revient automatiquement aux mesures actuelles trois secondes après relâchement du glissement sur le profil d’altitude. La sélection et les cercles disparaissent, et le centre de la vue 3D redevient automatique.
- Un nouveau glissement annule le délai précédent. Aucun retour automatique pendant un glissement actif, même si le doigt reste immobile sur un point. L’annulation d’un geste déclenche également le délai.
- Le suivi du geste ne redémarre plus lorsque la longueur du profil complet évolue pendant le déplacement.

ApplicationId et signatures Android conservés. APK debug et release dans `releases/`, avec empreintes SHA-256.

Validation : 82 tests unitaires réussis, builds debug/release et lint vital release réussis. Version debug installée et lancée par USB sur le OnePlus 8T ; version 0.4.4 / code 12 vérifiée. Pas de tests UI prolongés.
