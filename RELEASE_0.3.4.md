# E-BikeCockpit 0.3.4 — Lopez Alban

- Vitesses roue et moteur masquées lorsque l'ESP32 est déconnecté.
- Mode « tout » : les petites routes, chemins, cours d'eau et bâtiments ne sont
  plus exclus arbitrairement. Budgets de rendu indépendants par couche.
- Parcelles cadastrales IGN PCI Express en option dans le menu par appui long.
  Contours projetés sur le relief, références section/numéro à l'intérieur des
  polygones. Pas de noms de propriétaires ni de noms de lieux inventés.
- Les références suivent la caméra ; les textes qui se chevauchent sont masqués
  pour rester lisibles. Couverture locale autour du GPS, neuf zones fixes, cache
  privé 64 Mio, pagination bornée à 2 000 parcelles par zone (signalée si partielle).
  Cette couche est désactivée par défaut ; son activation interroge IGN pour la
  zone géographique. Elle ne constitue pas un document cadastral opposable.
- Chargement progressif des altitudes, cache de tuiles fixes et de géométrie,
  conservation des requêtes à la rotation, diagnostic GPS/3D activable.

Source cadastrale : IGN Parcellaire Express (PCI), issu de la DGFiP, licence ouverte.
Documentation : https://geoservices.ign.fr/parcellaire-express-pci

L'APK release conserve l'identifiant et la signature de la release 0.2.0.
Le téléphone de développement garde la variante debug pour préserver ses données.
