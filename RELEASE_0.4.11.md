# E-BikeCockpit 0.4.11 — versionCode 19

- Calibration passive du périmètre pendant les pauses, à partir de la trajectoire GPS et des compteurs de tours synchronisés : segment de 500 m minimum, précision GPS annoncée ≤ 7 m, rectitude ≥ 95 %.
- Au-delà de 1 % d’écart, Setting pulse pendant la pause avec « calibration de roue ». Le menu propose une application explicite lorsque l’ESP est connecté.
- Portions de repos directement colorées en magenta dans le tracé 3D et le profil, sans bordures.
- Distance et D+ descendus au-dessus de la légende en portrait et paysage ; statut IGN sous le D+.
- Légende et échelle de couleurs à la jonction 3D/profil ; version affichée entre l’auteur et GitHub.
- Seul le dernier APK signé est conservé dans le dépôt courant et les releases GitHub.

Validation : 101 tests unitaires réussis, compilation debug/release et lint vital release. Version 0.4.11 / code 19 installée et démarrée sur le OnePlus 8T. Calibration à confirmer sur un trajet réel avec l’ESP.
