# SerialScanner

Relevé des numéros de série de portables au téléphone, pour l'audit d'un lot en
reconditionnement — trente machines fermées, non démarrées, dont il faut sortir
la liste des numéros sans les taper à la main.

Application Android, hors ligne, sans compte ni serveur.

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
- **Lots** — un lot par palette ou par journée, marque déclarée à la création
  (Apple, Dell, HP, Lenovo, Asus), persistés en JSON local.
- **Contrôle** — photo pinçable et numéro éditable, alertes de format, de lettre
  proscrite (Apple n'emploie ni `O` ni `I`) et de voisinage (deux lignes à un
  caractère confondable près sont probablement le même capot relevé deux fois).
- **Export CSV** — `serial;model;emc;source;fiabilite;photo;horodatage`, à
  enregistrer ou à envoyer.

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
- Les longueurs des formats non-Apple viennent de la documentation constructeur
  et restent à confirmer sur étiquettes réelles.
- L'analyse tourne en 1080p ; sur une gravure usée, ML Kit perd parfois un
  caractère. Le refus est correct, mais un cliché pleine résolution au moment de
  la validation ferait mieux.
- La lampe du téléphone est contre-productive (reflet spéculaire coaxial) : il
  faut un éclairage rasant.
