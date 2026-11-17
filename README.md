# HyperGallery AI — Galerie Photo & Vidéo Native 120Hz (100 % Hors-Ligne)

**HyperGallery AI** est une application Android native ultra-rapide en **Kotlin 2.0 & Jetpack Compose Material 3**, conçue pour fonctionner **100 % hors-ligne** (aucune permission `INTERNET` dans le manifeste) avec accélération matérielle **GPU / NNAPI** pour la reconnaissance faciale locale et le chiffrement matériel **AES-256-GCM**.

## Fonctionnalités Clés

- **Confidentialité Mathématique Absolue** : Zéro dépendance cloud, zéro télémétrie, permission `android.permission.INTERNET` explicitement bannie du manifeste compilé.
- **Rendu 120 FPS & Formats Pro RAW** :
  - Thème **Noir Pur AMOLED (`#000000`)**, Sombre Ardoise & Material You.
  - **Coil 3** (cache RAM 25 %, cache disque 512 Mo, Hardware Bitmaps) + extracteur binaire haute définition pour fichiers **RAW** (`.dng`, `.nef`, `.cr2`, `.cr3`, `.arw`, `.orf`, `.rw2`), HEIC/AVIF, WebP/GIF animés et SVG.
  - Base **Room en mode WAL** couplée à **Paging 3** et tri naturel alphanumérique indexé SQLite.
- **Contrôle Total du Stockage & Dossiers Cachés** :
  - Indexeur hybride `MediaStore` + scan direct `java.io.File` (`MANAGE_EXTERNAL_STORAGE`) sur stockage interne, carte SD et clés USB OTG.
  - Explorateur d'arborescence réelle, épinglage de favoris, gestion en 1 clic des fichiers `.nomedia` et dossiers `.cachés`, moteur granulaire d'exclusions et mode Liste Blanche.
- **Biométrie Native & Coffre-Fort AES-256-GCM** :
  - Verrouillage de dossier sur place par empreinte (`BiometricPrompt` + code PIN de secours `PBKDF2WithHmacSHA256`).
  - Coffre-fort chiffré par blocs de 1 Mio **AES-256-GCM** adossé à l'**Android Hardware Keystore**, lu en flux mémoire vive (`VaultEncryptedFetcher` & `AesGcmVaultDataSource`) sans fichier temporaire en clair, protégé par `FLAG_SECURE` et reverrouillage instantané `ON_STOP` / extinction d'écran.
- **IA Locale Style Google Photos** :
  - Détection multi-échelles **BlazeFace** + alignement géométrique affine 5 points **ArcFace `112×112`** + embeddings **MobileFaceNet 192-D** + clustering **DBSCAN** cosinus incrémental avec contraintes humaines `cannot_link`, recherche par prénom, filtre combiné multi-visages et détecteur de doublons perceptuels **64-bit `dHash`**.
- **Visionneuse Tuilée 25×, Éditeur, Diaporama & Vidéo Pro** :
  - Zoom chirurgical par régions (`BitmapRegionDecoder` jusqu'à 25×), inspecteur EXIF + histogramme RVB/Luminance en direct + nettoyeur EXIF/GPS, éditeur photo complet, diaporama 5 transitions (dont *Ken Burns* centré sur les visages IA) et lecteur **Media3 ExoPlayer** gestuel (luminosité, volume, scrub fin, maintien `2.0×`, zoom `4×`, boucle A-B, PiP, sous-titres et capture PNG).

## Compilation Locale

```bash
./gradlew downloadFaceModels assembleRelease
```

Les APK signés sont générés dans `app/build/outputs/apk/release/` :
- `HyperGallery-AI-arm64-v8a-release.apk`
- `HyperGallery-AI-universal-release.apk`
