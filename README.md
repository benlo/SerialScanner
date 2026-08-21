# SerialScanner

Relevé des numéros de série de portables au téléphone, pour l'audit d'un lot en
reconditionnement — trente machines fermées, non démarrées, dont il faut sortir
la liste des numéros sans les taper à la main.

Application Android, hors ligne, sans compte ni serveur.

## Installer

L'APK se télécharge dans les [releases](https://github.com/benlo/SerialScanner/releases/latest),
s'ouvre sur le téléphone, et c'est tout — pas de magasin, pas de compte.

Deux avertissements à connaître, sans quoi on croit à un problème : Android
demande d'**autoriser l'installation depuis l'application qui a servi au
téléchargement** (le navigateur, en général), puis Play Protect signale une
application *non vérifiée*. C'est le régime normal de ce qui ne vient pas du
Play Store, pas un symptôme.

- **Android 8.0 (API 26) et au-dessus.** En dessous, l'installation est refusée.
- **Tout téléphone** : l'APK embarque les quatre architectures (arm64-v8a,
  armeabi-v7a, x86, x86_64), émulateurs compris.
- **Aucune dépendance aux services Google.** Le modèle de reconnaissance est
  dans l'APK : l'app fonctionne sans compte, sans réseau, sur un téléphone
  dégooglisé. Compter une centaine de mégaoctets installés, le modèle pèse.
- **Permissions** : caméra pour le scan, vibreur pour le retour à la validation.
  L'import passe par le sélecteur de photos du système, donc aucun accès au
  stockage n'est demandé. Les permissions réseau visibles dans le manifeste
  viennent des bibliothèques ; l'application ne fait aucun appel sortant.

Les versions suivantes s'installent par-dessus en gardant les lots enregistrés,
tant qu'elles sont signées avec la même clé.

La limite pratique n'est ni la version d'Android ni le modèle de téléphone,
c'est l'optique : un appareil qui ne fait pas le point à vingt centimètres lira
mal la gravure. C'est le zoom qui compense, pas l'approche.

## Le problème

Le numéro est gravé sous le capot : gris sur gris, sur alu brossé, souvent sur
une surface incurvée. Le grain du métal a le même contraste que la gravure, donc
tout seuillage qui préserve le texte préserve aussi le bruit. Tesseract, mesuré
sur les photos de ce parc avec trois prétraitements différents, rendait au mieux
`COBWBIJZOGLG` là où le numéro est `C02W61JZQ6LC` : huit caractères faux sur
douze. ML Kit, neuronal et embarqué, lit la même gravure juste — **sur l'image
brute**, pas sur une image binarisée.

## Le principe

**Ne jamais inventer un numéro.** C'est la seule règle qui compte : une ligne
vide se voit et se reprend, une ligne fausse se remonte au client. Trois choses
en découlent.

1. **Ancrage sur le mot-clé.** Le numéro est lu parce qu'il suit `Serial`,
   `S/N` ou `Service Tag`, jamais parce qu'il a la bonne forme — la ligne gravée
   contient aussi `20.0V`, `1.5A`, `A2337`, `3598`, tous candidats à un regex de
   dix à douze caractères. Le mot-clé commande le format attendu ; sans mot-clé,
   la ligne est marquée à reprendre.
2. **Deux lectures avant de valider**, à des instants — et si possible à des
   cadrages — différents. Un seul échantillon compté deux fois ne prouve rien.
3. **Trois états, et le vert se mérite.** Gris : la machine a lu, personne n'a
   vérifié. Vert : un opérateur a ouvert la photo et confirmé. Rouge : à
   reprendre. Chaque ligne garde la vignette de son recadrage, et l'écran de
   contrôle montre la gravure et le numéro dans la même vue.

Ce troisième point vient d'un lot réel : sur vingt-trois machines, deux numéros
étaient faux **et validés** — ML Kit avait commis deux fois la même erreur sur
la même gravure. La double lecture ne peut pas attraper ça. Seul l'œil le peut,
et seulement s'il voit la photo.

## Ce que l'app fait

- **Scan au fil de l'eau** — flux caméra, cadre de visée à trois états, zoom par
  paliers, retour haptique à la validation.
- **Import par lot** — sélection multiple depuis la galerie, seconde passe de
  reconnaissance recadrée sur la ligne repérée.
- **Lots** — un lot par palette ou par journée, persisté en JSON local, avec sa
  marque.
- **Contrôle** — photo pinçable et numéro éditable, alertes de format, de lettre
  proscrite (Apple n'emploie ni `O` ni `I`) et de voisinage (deux lignes à un
  caractère confondable près sont probablement le même capot relevé deux fois).
- **Export CSV** — `serial;model;emc;source;fiabilite;photo;horodatage`, à
  enregistrer ou à envoyer.

## Plusieurs marques

Un parc de reconditionnement n'est pas homogène : Apple grave, les autres
collent une étiquette, et les formats n'ont rien à voir. Chaque marque est un
**profil** — ce que l'étiquette dit d'elle-même (`ThinkPad`, `ProBook`,
`Latitude`, `ASUS`, `MacBook` ou `EMC`) et le format attendu derrière.

| Profil | Longueur | Indices sur l'étiquette |
|---|---|---|
| Apple | 10 ou 12, jamais de `O` ni de `I` | `MacBook`, `EMC` |
| Dell | 7 (Service Tag) | `DELL`, `SERVICE TAG`, `EXPRESS SERVICE` |
| Lenovo | 8 | `LENOVO`, `THINKPAD`, `THINKBOOK`, `MTM` |
| HP | 10 | `PROBOOK`, `ELITEBOOK`, `HEWLETT`, `HP` |
| Asus | 15 | `ASUS` |

Le mot-clé seul ne suffit pas à trancher : HP, Lenovo et Asus écrivent tous
`S/N` devant trois longueurs différentes. C'est donc le profil qui commande le
format, jamais le mot-clé seul, et **on n'accepte jamais l'union de tous les
formats** — ce serait rouvrir la porte au numéro plausible mais faux.

La marque déclarée à la création du lot prime sur la détection : trente Dell
d'affilée, autant le dire une fois. Sans déclaration, le profil se déduit de
l'étiquette, et à défaut c'est Apple qui s'applique, le plus contraint des cinq.
Ajouter une marque tient en une ligne dans `SerialParser.PROFILS`.

Seul le profil Apple est vérifié sur un parc réel ; les autres longueurs
viennent de la documentation constructeur.

## Construire

JDK 17, Android SDK 35. `local.properties` doit désigner le SDK.

```
./gradlew test           # la logique de lecture, en JVM pure
./gradlew installDebug   # sur un appareil branché
```

La logique qui décide d'un numéro — extraction, formats, distances,
séquencement des lectures — est du Kotlin sans dépendance Android, et testée à
ce titre. C'est la seule partie du projet où une erreur produit un mauvais
numéro sans que ça se voie.

### Publier une version

`versionCode` doit monter à chaque fois, sinon Android refuse d'installer
par-dessus la précédente :

```
# app/build.gradle.kts : versionCode = 2, versionName = "1.1"
./gradlew test && ./gradlew assembleRelease
gh release create v1.1 app/build/outputs/apk/release/app-release.apk
```

### Signature release

Le build release est signé si `keystore.properties` existe à la racine :

```
storeFile=/chemin/vers/votre.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Sans ce fichier, `./gradlew assembleRelease` produit un APK non signé.
Le keystore et ce fichier ne sont pas dans le dépôt et ne doivent jamais y être.

## Les numéros du dépôt

Les numéros de série qui apparaissent dans les tests, les planches d'essai et la
documentation sont **transposés** : ce sont ceux d'un parc réel, passés par une
substitution caractère à caractère qui préserve tout ce que les tests éprouvent
— longueur, code usine, alphabet, classes de confusion optique, distances entre
voisins, groupement des codes modèle. Les erreurs de lecture citées sont donc
réelles dans leur forme, mais ne désignent aucune machine existante. Les
machines, elles, appartiennent à un client.

## Limites connues

- Le format 11 caractères (Mac d'avant 2010) est refusé : l'accepter validerait
  toute lecture de douze tronquée d'un caractère.
- L'analyse tourne en 1080p ; sur une gravure usée, ML Kit perd parfois un
  caractère. Le refus est correct, mais un cliché pleine résolution au moment de
  la validation ferait mieux.
- La lampe du téléphone est contre-productive (reflet spéculaire coaxial) : il
  faut un éclairage rasant.
