# SerialScanner

Relever au téléphone les numéros de série d'un lot d'ordinateurs portables,
sans les taper à la main — machines fermées, non démarrées, numéro gravé sous le
capot ou collé sur l'étiquette. Inventaire d'un parc, retour de location, entrée
d'un lot en reconditionnement : le geste est le même, trente capots à lire.

Application Android, hors ligne, sans compte ni serveur.

**Aujourd'hui, l'app est faite pour les Mac.** C'est sur des MacBook qu'elle a
été construite et éprouvée — un lot de vingt-trois machines, deux photos de
référence, et les erreurs de lecture qui ont façonné chacun de ses garde-fous.
Les autres marques fonctionnent, mais aucune n'a la même épaisseur d'usage
derrière elle. **Le multi-marque est la direction, pas encore l'état.**

Ce qui a changé le 22/08/2026 : les [gabarits](#gabarits). Une étiquette
s'étalonne une fois — on y désigne le mot-clé et le numéro — et l'application
sait ensuite la lire, quelle que soit la marque, sans qu'aucun format ait été
codé pour elle. Trois dispositions réelles ont été relevées à ce jour : capot
gravé Apple, Asus X512U, Lenovo Yoga. La section
[Plusieurs marques](#plusieurs-marques) dit ce que valent encore les profils.

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

**Venant de la 1.0** : l'identifiant de l'application a changé après cette
version. Android voit donc une application différente, qui s'installe à côté
au lieu de remplacer — il faut **désinstaller la 1.0 d'abord**, et ses lots
partent avec elle. Exporter les CSV avant.

La limite pratique n'est ni la version d'Android ni le modèle de téléphone,
c'est l'optique : un appareil qui ne fait pas le point à vingt centimètres lira
mal la gravure. C'est le zoom qui compense, pas l'approche.

## Le problème

Il y a deux matières, et elles ne résistent pas de la même façon.

**La gravure**, sous les capots Apple : gris sur gris, sur alu brossé, souvent
sur une surface incurvée. Le grain du métal a le même contraste que la gravure,
donc tout seuillage qui préserve le texte préserve aussi le bruit. Tesseract,
mesuré sur les photos de ce parc avec trois prétraitements différents, rendait
au mieux `COBWBIJZOGLG` là où le numéro est `C02W61JZQ6LC` : huit caractères
faux sur douze. ML Kit, neuronal et embarqué, lit la même gravure juste — **sur
l'image brute**, pas sur une image binarisée.

**L'étiquette collée**, chez tous les autres : optiquement facile, noir sur
blanc. Elle résiste ailleurs. D'abord par sa mise en page — un Asus X512U écrit
`SN:` et la durée de garantie sur une ligne, le numéro seul sur la ligne
suivante, alors qu'un capot gravé met tout sur une seule ligne. Ensuite parce
que l'aisance de lecture est trompeuse : sur cette même étiquette, ML Kit a
rendu **deux fois de suite** `KINOCV03K34002H` là où l'étiquette porte
`K1N0CV03K34002H` — un `I` pour un `1`, un `O` pour un `0`. Deux lectures
concordantes et fausses, sur une étiquette nette.

## Le principe

**Ne jamais inventer un numéro.** C'est la seule règle qui compte : une ligne
vide se voit et se reprend, une ligne fausse se remonte au client. Trois choses
en découlent.

1. **Ancrage sur le mot-clé.** Le numéro est lu parce qu'il suit `Serial`,
   `S/N`, `SN:` ou `Service Tag`, jamais parce qu'il a la bonne forme — la ligne
   gravée contient aussi `20.0V`, `1.5A`, `A2337`, `3598`, tous candidats à un
   regex de dix à douze caractères. Le mot-clé commande le format attendu ; sans
   mot-clé, la ligne est marquée à reprendre. Le numéro peut être sur la ligne du
   mot-clé ou juste dessous — mais alors la ligne doit être **nue**, un seul bloc
   alphanumérique et rien d'autre, sans quoi la garantie `24M` imprimée à côté
   ferait un candidat.

   Avec un [gabarit](#gabarits), l'ancrage devient **géométrique** : le mot-clé
   ne désigne plus la ligne mais une zone, et tout ce qui tombe hors d'elle
   n'est pas analysé. Un garde-fou d'une autre nature — `24M` et `MFD:` cessent
   d'être des candidats, non parce qu'ils échouent à un test mais parce qu'on ne
   les regarde pas.
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
- **Lots** — un lot par série de machines, par client ou par journée, persisté en
  JSON local, avec son gabarit.
- **Gabarits** — une bibliothèque de modèles d'étiquette, étalonnés une fois et
  réutilisés d'une palette à l'autre. Voir [Gabarits](#gabarits).
- **Contrôle** — photo pinçable et numéro éditable, alertes de format, de lettre
  proscrite (ni Apple ni Asus n'emploient `O` ou `I`) et de voisinage (deux
  lignes à un caractère confondable près sont probablement le même capot relevé
  deux fois). La correction est **proposée, jamais appliquée d'office** : c'est
  la photo, juste au-dessus, qui tranche.
- **Export CSV** — `serial;model;emc;source;fiabilite;photo;horodatage`, à
  enregistrer ou à envoyer.

## Gabarits

Un gabarit dit **où se trouve le numéro sur une étiquette**, et quelle forme il
a. Il s'étalonne une fois sur un modèle de machine, puis sert à toutes les
palettes de ce modèle.

C'est la réponse au problème que les profils marque ne savaient pas traiter :
chaque fabricant dispose son étiquette autrement, et coder chaque disposition
dans l'application ne passe pas à l'échelle. Trois mises en page rencontrées sur
trois machines réelles, et le même objet les exprime toutes sans une ligne de
code spécifique :

| Machine | Mot-clé | Où est le numéro |
|---|---|---|
| MacBook (capot gravé) | `Serial` | à droite, même ligne |
| Asus X512U | `SN:` | dessous, aligné à gauche |
| Lenovo Yoga | `Serial Number` | dessous, décalé à gauche |

### Comment il tient

**Rien n'est stocké en pixels.** Une photo suivante est prise à une autre
distance et à un autre angle ; un rectangle absolu n'y voudrait rien dire. La
zone est exprimée en multiples de la **hauteur de la boîte du mot-clé**, origine
à son coin haut-gauche. Que l'étiquette occupe 200 ou 900 pixels dans le cadre,
les mêmes coefficients désignent le même endroit.

Au scan, l'application cherche le mot-clé dans l'image, projette la zone depuis
sa boîte, et n'analyse **que** ce qu'elle contient.

### Ce qu'il porte

- le **mot-clé** réellement imprimé, avec la position du numéro par rapport à lui ;
- la **longueur** du numéro, en caractères utiles — relevée sur le numéro que
  l'opérateur a désigné, pas devinée dans une table ;
- l'**alphabet** : si la référence n'emploie ni `O` ni `I`, on les tient pour
  proscrits, et les y voir devient une erreur de lecture signalée ;
- la **photo de référence** et les deux rectangles désignés, qui rendent
  l'étalonnage vérifiable après coup.

Le format venant du gabarit, **déclarer une marque n'est plus nécessaire**. Une
étiquette dont aucun profil ne connaît le format — Acer, MSI, Fujitsu — se lit
dès lors qu'on l'a étalonnée une fois.

### L'étalonner

**Automatiquement**, pendant un scan : tant qu'un lot n'a pas de gabarit,
l'application cherche dans chaque image un mot-clé et un numéro plausible, et en
tire la géométrie. Deux images concordantes avant de l'adopter — ce qu'on
confirme là est la **géométrie**, pas les caractères : un numéro mal lu a quand
même la bonne boîte.

**À la main**, quand l'automatique renonce. Il renonce dès qu'il y a deux
candidats, et c'est délibéré : sur une étiquette dense, trancher reviendrait à
deviner, et un gabarit deviné fausserait toute la palette — systématiquement. On
photographie alors l'étiquette, et on **désigne deux mots reconnus** : le
mot-clé, puis le numéro. Toucher un mot plutôt que tracer un rectangle donne sa
boîte au pixel près *et* son contenu, dont le gabarit a besoin pour retrouver
son ancre sur les photos suivantes.

Un gabarit ne se supprime pas tant qu'un lot s'en sert — et l'application dit
lesquels.

## Plusieurs marques

Un parc n'est jamais homogène : Apple grave, les autres collent une étiquette,
et les formats n'ont rien à voir. Chaque marque est un **profil** — ce que
l'étiquette dit d'elle-même (`ThinkPad`, `ProBook`, `ASUS`, `MacBook` ou `EMC`)
et le format attendu derrière.

| Profil | Longueur | Indices sur l'étiquette | Éprouvé ? |
|---|---|---|---|
| Apple | 10 ou 12, jamais de `O` ni de `I` | `MacBook`, `EMC` | **oui**, lot de 23 machines |
| Asus | 15, jamais de `O` ni de `I` | `ASUS` | une étiquette, un X512U |
| Dell | 7 (Service Tag) | `DELL`, `SERVICE TAG`, `EXPRESS SERVICE` | non, doc constructeur |
| Lenovo | 8 | `LENOVO`, `THINKPAD`, `THINKBOOK`, `MTM` | non, doc constructeur |
| HP | 10 | `PROBOOK`, `ELITEBOOK`, `HEWLETT`, `HP` | non, doc constructeur |

Le mot-clé seul ne suffit pas à trancher : HP, Lenovo et Asus écrivent tous
`S/N` devant trois longueurs différentes. C'est donc le profil qui commande le
format, jamais le mot-clé seul, et **on n'accepte jamais l'union de tous les
formats** — ce serait rouvrir la porte au numéro plausible mais faux.

**Ces profils ne sont plus le chemin principal.** Détecter la marque au scan ne
marche pas : la reconnaissance ne travaille que sur le cadre de visée, resserré
sur le numéro, et le nom du fabricant est imprimé ailleurs sur l'étiquette.
Vérifié au logcat sur le X512U — pas une seule image du cadre ne contenait le
mot `ASUSTek`. Faute d'indice, c'est Apple qui s'appliquait, et un numéro Asus
de quinze caractères se faisait refuser par un format qui en attend douze.

C'est ce constat qui a fait naître les [gabarits](#gabarits) : le format vient
maintenant du numéro que l'opérateur désigne lui-même, et la création d'un lot
ne demande plus de marque. Les profils restent le **repli sans gabarit**, où
Apple s'applique — le plus contraint, et mieux vaut refuser une étiquette
inconnue que valider un numéro douteux. Ils servent aussi à l'import de photos,
plus larges que le cadre de visée, où le nom du fabricant est souvent visible.

Ajouter une marque tient toujours en une ligne dans `SerialParser.PROFILS`, mais
c'est devenu l'exception : étalonner un gabarit ne demande pas de toucher au
code.

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

Une exception : `K1N0CV03K34002H`, le numéro Asus, n'est pas transposé. C'est
la machine de l'auteur, pas celle d'un client, et c'est ce qui permet de citer
la sortie ML Kit brute à côté de la vérité.

## Limites connues

- Le format 11 caractères (Mac d'avant 2010) est refusé : l'accepter validerait
  toute lecture de douze tronquée d'un caractère.
- L'analyse tourne en 1080p ; sur une gravure usée, ML Kit perd parfois un
  caractère. Le refus est correct, mais un cliché pleine résolution au moment de
  la validation ferait mieux.
- La lampe du téléphone est contre-productive (reflet spéculaire coaxial) : il
  faut un éclairage rasant.
- Hors Apple, un lot sans gabarit lit mal : faute de format connu, il retombe
  sur Apple et refuse les numéros d'une autre longueur. Étalonner un gabarit, ou
  déclarer la marque par le menu du lot.
- L'alphabet d'un gabarit — `O` et `I` employés ou non — est déduit d'un **seul**
  numéro de référence. Si celui-ci n'en contient aucun par hasard, ils seront
  tenus pour proscrits à tort. La conséquence reste mesurée : une alerte et une
  correction proposée au contrôle, jamais une réécriture.
- La géométrie d'un gabarit ne gère pas l'inclinaison : les boîtes de ML Kit sont
  alignées sur les axes de l'image. Une étiquette franchement de biais décale la
  zone. Deux points clés au lieu d'un donneraient la rotation.
- ML Kit ne garantit pas l'ordre des lignes qu'il rend : sur certaines images
  d'une étiquette Asus, le numéro sortait avant son mot-clé. Ces images-là ne
  rendent rien, ce qui est correct, mais le scan met quelques secondes de plus
  à accrocher.
- **Les codes-barres et QR ne sont pas lus.** Les étiquettes collées en portent
  presque toujours un, qui contient le numéro avec une somme de contrôle — donc
  structurellement plus sûr que n'importe quelle lecture de caractères. C'est la
  piste la plus rentable pour les marques non-Apple.
