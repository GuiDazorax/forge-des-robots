# Forge des Robots

Jeu de combat de robots en pixel art, pour Android. Crée ton robot (couleurs, casque, canon, stats) et affronte l'ordinateur, un ami sur le même téléphone, ou jusqu'à 7 amis en Bluetooth.

## Installer l'app

1. Ouvre la page **Releases** du dépôt sur ton téléphone et télécharge `forge-des-robots.apk` (version « apk-latest »).
2. Ouvre le fichier. Android demande d'autoriser l'installation depuis le navigateur : accepte.
3. Lance **Forge des Robots**.

Chaque push sur `main` refabrique l'APK automatiquement (onglet Actions).

## Jouer en Bluetooth (2 à 8 joueurs)

1. Tout le monde choisit son robot dans « Joueur 1 », puis le mode **Bluetooth**.
2. Un joueur touche **Héberger** et accepte que son téléphone soit visible.
3. Les autres touchent **Chercher une partie**, puis le nom du téléphone de l'hôte.
4. Quand tout le monde est dans le salon, l'hôte touche **Lancer la partie**. Dernier robot debout gagne.

Au-delà de 4 joueurs, le Bluetooth peut ralentir un peu la synchronisation.

## Structure

- `app/src/main/assets/index.html` : le jeu (HTML/JS, un seul fichier).
- `app/src/main/java/fr/forgedesrobots/BtBridge.java` : serveur et client Bluetooth (RFCOMM), exposé au jeu sous `window.AndroidBT`.
- `MainActivity.java` : affiche le jeu dans une WebView plein écran.
