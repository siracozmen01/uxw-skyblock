# uxw-skyblock

High-performance, distributed Skyblock core engine for Paper and Folia.

## Architecture

The project is structured following clean architecture / hexagonal principles:

- **`api`**: Public integration API, events, and service interfaces for external plugins.
- **`core`**: Pure domain models, business use cases, coordinate math, and authority rules (no Minecraft server dependencies).
- **`persistence-adapter`**: Relational storage adapters (SQLite, MySQL, PostgreSQL) with automated Flyway database migrations.
- **`bukkit-adapter`**: Paper / Folia platform adapter, custom event listeners, GUIs, commands, and scheduled tasks.
- **`rest-adapter`**: Standalone embedded HTTP service for external status monitoring and cluster queries.

## Requirements

- **Java**: 21 or higher
- **Server**: Paper or Folia 1.21+

## Building

```bash
./gradlew build
```

## Testing

```bash
./gradlew test
```

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
