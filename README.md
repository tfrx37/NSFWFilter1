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
```

---

## **🛠️ Spécifications Techniques**

ComposantTechnologie / Modèle
LangageKotlinAPI MinimaleAndroid 11 (API level 30)Modèle IAMobileNetV2 NSFW Quantized (nsfw_model.tflite)PrétraitementNormalisation Keras [-1.0, 1.0], Resize Bilinéaire 224x224ConcurrenceCoroutines Kotlin (SupervisorJob, Dispatchers.Default), AtomicLong

---

## **⚙️ Configuration & Ajustement de la Sensibilité**

Les seuils de sensibilité peuvent être ajustés directement dans NSFWClassifier.kt :
```text
// Dans NSFWClassifier.kt
private const val CROP_THRESHOLD = 0.35f               // Seuil pour les nœuds d'images ciblés
private const val GLOBAL_THRESHOLD = 0.35f             // Seuil pour l'écran complet
private const val STRICT_NO_SKIN_THRESHOLD = 0.55f     // Seuil renforcé sans teinte chair (banques)
```
Et la fréquence d'analyse dans FilterService.kt :
```text
// Dans FilterService.kt
private const val NORMAL_CAPTURE_INTERVAL_MS = 350L     // Intervalle pendant le défilement
private const val STATIC_RECHECK_INTERVAL_MS = 1200L   // Intervalle de ré-analyse sur écran fixe
```

---

## **🚀 Installation & Compilation**

### Prérequis
* Android Studio Ladybug (ou version ultérieure)
* JDK 17
* SDK Android 34 (Android 14) ou supérieur
### Étapes de Build
1. Cloner le dépôt :
```text
git clone https://github.com/tfrx37/NSFWFilter1.git
```
3. Ouvrir le projet dans Android Studio.
4. Synchroniser Gradle (Sync Project with Gradle Files).
5. Compiler et installer sur un appareil Android sous Android 11 ou supérieur :
```text
./gradlew installDebug
```

---

## **🤝 Contribution**

Les contributions sont les bienvenues ! Voici comment participer :
1. Remonter un bug ou proposer une fonctionnalité via les Issues GitHub.
2. Forquer le projet (Fork).
3. Créer une branche dédiée (git checkout -b feature/AmeliorationFiltre).
4. Commiter tes modifications (git commit -m 'Ajout de la fonction X').
5. Pousser la branche (git push origin feature/AmeliorationFiltre).
6. Ouvrir une Pull Request.

---

## **📄 Licence**

Ce projet est sous licence MIT - voir le fichier LICENSE pour plus de détails.

---

## **⚠️ Avertissement (Disclaimer)**

Cet outil est fourni à titre d'aide personnelle et éducative. Bien que conçu pour maximiser la précision, le filtrage basé sur l'IA peut présenter des faux positifs ou faux négatifs en fonction du contexte visuel et des applications utilisées.
