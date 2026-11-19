# Gems (Paper 1.21.11)

Build : `mvn clean package` -> `target/Gems.jar` (Java 21), à mettre dans `plugins/`.
Commande : `/gem give <joueur> <gem> [nombre]`, `/gem list`, `/gem reload` (permission `gems.admin`).
Gems : white, red, green, pink, yellow, blue, black, purple, orange, block.
Les passifs marchent avec la gem n'importe où dans l'inventaire, les actifs avec la gem en main.
Durées, cooldowns et valeurs : `plugins/Gems/config.yml`.
