# Documentation Nomadix

Documentation technique du projet **Nomadix** (plateforme de voyage en microservices).
Tous les diagrammes sont ecrits en **Mermaid** : ils vivent dans le depot, se versionnent
avec le code et se mettent a jour dans la meme pull request que le code.

> Point d'entree du rapport complet (audit, scenarios, Swagger, verdict d'architecture) :
> [`../update2.md`](../update2.md). Ce dossier fournit les **diagrammes** et les
> **references courtes** ; `update2.md` fournit le **recit** et les preuves.

## Sommaire

| Fichier | Contenu | Diagrammes |
|---|---|---|
| [01-architecture.md](01-architecture.md) | Contexte, conteneurs, ports, communication, Gateway, securite, deploiement | 5 |
| [02-workflows.md](02-workflows.md) | Workflows de bout en bout : CRUD, saga de reservation, compensation, circuit breaker, favoris, profilage, RabbitMQ | 12 |
| [03-domain-model.md](03-domain-model.md) | Modele de domaine, DTO, bases de donnees, regles de scoring | 4 |
| [04-api-reference.md](04-api-reference.md) | Catalogue des endpoints, matrice de droits, codes de retour, verification des specs | 2 |
| [05-diagrammes.html](05-diagrammes.html) | Page HTML autonome : architecture de deploiement, domaine, saga, topologie RabbitMQ, Eureka/Gateway/Feign (SVG, aucun CDN) | 5 |
| [06-scenarios-test.md](06-scenarios-test.md) | Scenarios OpenFeign et RabbitMQ, parcours UI, commandes curl de verification, depannage | 1 |

Les images historiques (`architecture_microservices.png`, `diagramme_de_classe.png`,
`diagramme_de_domaines.png`) sont conservees pour les rapports deja rendus. **Les fichiers
Markdown de ce dossier sont la version maintenable** : en cas de divergence, ils font foi.

`05-diagrammes.html` est la version imprimable (Ctrl+P) des schemas : ouvrir le fichier
dans un navigateur, aucune connexion reseau n'est necessaire.

## Comment lire les diagrammes

- **GitHub / GitLab** : rendu automatique des blocs ` ```mermaid `.
- **VS Code** : extension *Markdown Preview Mermaid Support* puis `Ctrl+Shift+V`.
- **Ligne de commande** : `npx @mermaid-js/mermaid-cli -i 02-workflows.md -o workflows.md`
  (ou copier un bloc sur <https://mermaid.live>).
- **Export image** : `mmdc -i diagram.mmd -o diagram.png -w 1600`.

## Conventions

- Langue : francais, sans accents dans les diagrammes (compatibilite des renderers CLI).
- Un service = une couleur mentale : Java/Spring (bleu), Python/FastAPI (vert),
  Node/Express (orange), infrastructure (gris).
- Toute fleche porte son **moyen de transport** : `JWT`, `Feign`, `AMQP`, `JDBC`.
- Les identifiants (`travelerId`, `flightId`) sont des **references logiques** :
  il n'existe aucune cle etrangere entre deux bases.

## Regle de maintenance

1. Une route ajoutee dans un controleur doit apparaitre dans le Swagger du service
   **et** dans `04-api-reference.md`.
2. Un flux inter-services ajoute doit apparaitre dans `02-workflows.md`.
3. Un composant ou un port ajoute doit apparaitre dans `01-architecture.md`.
4. Les tests de contrat (`pytest`, `npm test`) verifient que la spec publiee contient
   bien les routes reellement servies.
