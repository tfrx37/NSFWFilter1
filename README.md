# 🛡️ NSFWFilter for Android

**NSFWFilter** est un service d'accessibilité Android open-source conçu pour détecter et masquer en temps réel les contenus visuels explicites ou suggestifs (NSFW) affichés à l'écran, tout en garantissant la confidentialité absolue de l'utilisateur grâce à une inférence 100 % locale.

---

## ✨ Fonctionnalités clés

* **🔒 Analyse 100 % On-Device (Confidentialité)** : Traitement local via TensorFlow Lite. Aucune capture d'écran ni donnée visuelle ne quitte l'appareil.
* **⚡ Capture & Détection Ultra-rapide** : Analyse ciblée des nœuds d'images via l'API `AccessibilityNodeInfo` et fallback automatique sur l'analyse globale d'écran (Android 11+ `TakeScreenshotCallback`).
* **🎯 Contournement intelligent des faux positifs** :
  * Détection automatique des écrans système, d'authentification biométrique (empreinte digitale) et des applications bancaires pour éviter les blocages intempestifs.
  * Ajustement dynamique des seuils de confiance basé sur le ratio de teinte chair (`skinRatio`).
* **🛡️ Protection contre la désactivation** : Interception des tentatives d'arrêt ou de désinstallation dans les Paramètres système Android.
* **💾 Gestion mémoire & Stabilité** :
  * Recyclage strict des `Bitmap` Kotlin pour éviter les fuites mémoire (`OutOfMemoryError`).
  * Mécanisme de *Watchdog* et *Circuit Breaker* pour prévenir les blocages de thread ou surchauffes lors des captures intensives.

---

## 🏗️ Architecture du Projet

```text
com.example.nsfwfilter/
├── FilterService.kt      # AccessibilityService : gestion de la boucle de capture,
│                         # analyse des nœuds d'écran, overlay et blocage paramètres.
├── NSFWClassifier.kt     # Moteur TensorFlow Lite (MobileNetV2) : pré-traitements Keras,
│                         # calcul de skinRatio et évaluation des scores NSFW.
└── MainActivity.kt       # Interface d'activation du service et des autorisations.
