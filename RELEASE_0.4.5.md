# E-BikeCockpit 0.4.5 — versionCode 13

- Pendant l’exploration du profil, la caméra est centrée sur le barycentre des points du parcours complet, au lieu du point sélectionné. Le cadrage inclut le parcours complet pendant cette consultation.
- La rotation et les ajustements automatiques de perspective sont suspendus pendant le glissement et la consultation historique. Les gestes manuels de rotation, inclinaison et zoom restent disponibles.
- Le cercle de sélection suit maintenant la projection réelle du point dans la scène 3D, indépendamment du centre de rotation.
- Toucher/manipuler la 3D suspend le retour au direct. Le délai de trois secondes redémarre seulement lorsque les gestes sur la 3D et le profil sont tous relâchés. Un geste annulé ou la mise en pause de la vue libère aussi cet état.
- Le retour au direct rétablit le cadrage et la caméra automatiques.

Identifiant Android et clés de signature conservés.

Validation : 84 tests unitaires réussis, compilation debug/release et lint vital release réussis. APK debug installé et lancé sur le OnePlus 8T par mise à jour USB, version 0.4.5 / code 13 vérifiée. Pas de tests UI prolongés.
