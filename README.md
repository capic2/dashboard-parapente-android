# Application Android — Dashboard Parapente

Cette application Android ouvre le dashboard de production dans une WebView sécurisée :
`https://parapente.capic.ignorelist.com`.

Les liens du dashboard restent dans l'application. Les liens qui quittent ce domaine (par exemple
Intervals.icu) s'ouvrent dans le navigateur Android par défaut.

## Prérequis

- JDK 17
- Android SDK Platform 35
- Android Studio Ladybug ou version plus récente

## Ouvrir et générer l'APK

1. Ouvrir ce dossier dans Android Studio.
2. Laisser Gradle synchroniser les dépendances.
3. Lancer l'application sur un téléphone ou un émulateur, ou utiliser **Build > Build APK(s)**.

En ligne de commande, avec le JDK configuré :

```sh
./gradlew assembleDebug
```

L'APK de débogage est généré dans `app/build/outputs/apk/debug/`.
