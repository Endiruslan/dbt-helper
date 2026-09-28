# dbt Helper

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/31663-dbt-helper?label=JetBrains%20Marketplace)](https://plugins.jetbrains.com/plugin/31663-dbt-helper)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31663-dbt-helper)](https://plugins.jetbrains.com/plugin/31663-dbt-helper)

A JetBrains IDE plugin that brings **lineage visualization**, **code intelligence**, and **command runner** for [dbt](https://www.getdbt.com/) projects.

Works with **IntelliJ IDEA**, **PyCharm**, **DataSpell**, and other JetBrains IDEs (2025.1+).

---

## Features

- **Lineage graph** — interactive DAG of your dbt models with click-to-explore navigation. Single click previews a model and its dependencies; double click refocuses the graph and opens the file.
- **Docs sidebar** — columns, tests, SQL, and metadata for the selected model, side-by-side with the graph.
- **Code intelligence** — autocomplete and go-to-definition for `ref()`, `source()`, and `macro()`; hover for column info; warnings on unresolved references. Works in `.sql` and Jinja files.
- **Runner** — run, test, compile, preview, and regenerate docs without leaving the IDE. Target selector and live output log.
- **Copy & paste helpers** — copy SQL with refs resolved to `db.schema.table`, or paste SQL with table names converted back into `ref()` / `source()` calls.

---

## Installation

### From JetBrains Marketplace (recommended)
In your IDE: **Settings** → **Plugins** → **Marketplace** → search for **dbt-helper** → **Install**, then restart the IDE.

Or open the [plugin page](https://plugins.jetbrains.com/plugin/31663-dbt-helper) and click **Install to IDE**.

### From Disk
1. Download the latest `dbt-helper-x.x.x.zip` from [Releases](https://github.com/Endiruslan/dbt-helper/releases)
2. In your IDE: **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk...**
3. Select the ZIP file and restart the IDE

### Requirements
- JetBrains IDE **2025.1** or later
- A dbt project with `manifest.json` (run `dbt compile` or `dbt docs generate` first)
- dbt CLI installed and accessible

---

## Setup

1. Open a project containing `dbt_project.yml`
2. The plugin auto-detects your dbt project root and parses `target/manifest.json` (or the **Target directory** from settings)
3. Open the **dbt Helper** tool window (bottom panel)

### Settings
**Settings** → **Tools** → **dbt Helper**

| Setting | Description | Default |
|---------|-------------|---------|
| dbt executable path | Path to dbt CLI binary | `dbt` (auto-detected) |
| Project root override | Manual dbt project root | auto-detect |
| Target directory | Where dbt writes `manifest.json` / `catalog.json`, relative to the project root or absolute. Set it if you use `--target-path` / `DBT_TARGET_PATH` | `target` |
| Profiles directory | Directory with `profiles.yml` | `$DBT_PROFILES_DIR`, then project root, then `~/.dbt` |
| Active target | Target from profiles.yml | default target |
| Upstream/Downstream depth | Lineage graph depth | 5 / 5 |
| Edge style | Lineage edge rendering | round-taxi |
| Layout direction | Graph orientation | Left → Right |
| Preview row limit | Max rows for `dbt show` | 10 |
| Show exposures | Display exposures in lineage | on |
| System notifications | Native OS notifications | on |

---

## Keyboard Shortcuts

| Action | macOS | Windows/Linux |
|--------|-------|---------------|
| Copy for Target DB | `Cmd+Shift+C` | `Ctrl+Shift+C` |
| Paste as dbt Refs | `Cmd+Shift+V` | `Ctrl+Shift+V` |

---

## Building from Source

```bash
git clone https://github.com/Endiruslan/dbt-helper.git
cd dbt-helper
./gradlew buildPlugin
```

The plugin ZIP will be at `build/distributions/dbt-helper-x.x.x.zip`.

To run a development instance:
```bash
./gradlew runIde
```

### Releasing
1. Bump `pluginVersion` in `gradle.properties` and add `<change-notes>` in `plugin.xml`
2. Publish a GitHub release tagged `v<pluginVersion>`

The [Release workflow](.github/workflows/release.yml) then uploads the plugin to JetBrains Marketplace (needs the `PUBLISH_TOKEN` repo secret) and attaches the ZIP to the release.

---

## License

[MIT](LICENSE)

---

*This plugin is not affiliated with or endorsed by dbt Labs.*
