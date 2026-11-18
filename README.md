# Optimus Launcher

Eigen, lichte Android car-launcher voor de head unit "Optimus Black"
(Denago DNG-A206, Rockchip RK3326, Android 8.1). Vervangt AGAMA.

## Status: 0.0.1 (skelet)

Deze eerste versie bewijst de pijplijn en het home-scherm:

- Draait als HOME-launcher (donker thema, liggend).
- Grote klok + datum (Nederlands).
- Zes knoppen die apps starten op pakketnaam:
  Navigatie (Google Maps), Muziek (Spotify), Telefoon (BtPhone),
  Radio (FlyRadio), Apps (placeholder), Instellingen (Android).
- Ontbreekt een app, dan meldt de knop dat netjes.

### Nog niet in 0.0.1
Dag/nacht via koplampen, snelheid, GPS-ritmeter, meldingen, kompasband,
verbruiksmeter, Q-Park-spiegel, zelf-update. Die staan op de roadmap.

## Bouwen
Push naar `main` of start de workflow handmatig. GitHub Actions bouwt
de debug-APK en zet die als artifact (`optimus-launcher-debug`) klaar.

## Installeren op de tablet
```
adb connect 192.168.178.198:5555
adb install -r app-debug.apk
```
Daarna één keer op HOME drukken en Optimus Launcher als standaard kiezen.

## Build-opzet
- minSdk 27 (Android 8.1), targetSdk 34
- Android Gradle Plugin 8.5.2, Gradle 8.7, JDK 17
- Puur Android-framework (Java), geen externe dependencies
