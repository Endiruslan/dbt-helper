# dbt Helper

A JetBrains IDE plugin that brings **lineage visualization**, **code intelligence**, and **command runner** for [dbt](https://www.getdbt.com/) projects.

Works with **IntelliJ IDEA**, **PyCharm**, **DataSpell**, and other JetBrains IDEs (2025.1+).

---

## Features

### Lineage Graph
Interactive DAG visualization powered by [Cytoscape.js](https://js.cytoscape.org/) — see upstream and downstream dependencies for any model at a glance.

- Click nodes to navigate to source files
- Expand boundary nodes to explore deeper
- Configurable depth, layout direction, and edge style
- Shows models, sources, seeds, snapshots, exposures, and tests
- Auto-updates when manifest changes
- Adapts to light/dark IDE themes

### Code Intelligence
- **Autocompletion** — `ref('`, `source('`, `macro('` suggestions with descriptions
- **Go to Definition** — Ctrl/Cmd+Click on `ref()` / `source()` to jump to the model file
- **Documentation on Hover** — see column info, materialization, tags, and descriptions
- **Annotations** — unresolved `ref()` / `source()` calls highlighted as warnings
- Works in `.sql`, `.jinja`, and `.jinja2` files

### Runner
Run dbt commands directly from the IDE:

- **Run** / **Test** / **Compile** the current model with one click
- **Full Refresh** checkbox for incremental models
- **Preview** — execute `dbt show` on any model or selected SQL (`Cmd+Shift+R`)
- **Docs Generate** — rebuild documentation
- **Target selector** — switch between dbt targets (dev, prod, etc.)
- **Stop** button to cancel running commands
- Streaming output log with process status
- Native OS notifications when commands finish (configurable)

### Copy & Paste
- **Copy for Target DB** (`Cmd+Shift+C`) — copies SQL with `ref()` / `source()` replaced by `database.schema.table`
- **Paste as dbt Refs** (`Cmd+Shift+V`) — pastes SQL with table names replaced by `ref()` / `source()` calls

### Docs Viewer
Browse model documentation in a built-in panel — columns, descriptions, tags, materialization, and dependencies.

---

## Installation

### From Disk (Development Build)
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
2. The plugin auto-detects your dbt project root and parses `target/manifest.json`
3. Open the **dbt Helper** tool window (bottom panel)

### Settings
**Settings** → **Tools** → **dbt Helper**

| Setting | Description | Default |
|---------|-------------|---------|
| dbt executable path | Path to dbt CLI binary | `dbt` (auto-detected) |
| Project root override | Manual dbt project root | auto-detect |
| Active target | Target from profiles.yml | default target |
| Upstream/Downstream depth | Lineage graph depth | 5 / 5 |
| Edge style | Lineage edge rendering | bezier |
| Layout direction | Graph orientation | Left → Right |
| Preview row limit | Max rows for `dbt show` | 10 |
| Show test nodes | Display tests in lineage | off |
| Show exposures | Display exposures in lineage | on |
| System notifications | Native OS notifications | on |

---

## Keyboard Shortcuts

| Action | macOS | Windows/Linux |
|--------|-------|---------------|
| Copy for Target DB | `Cmd+Shift+C` | `Ctrl+Shift+C` |
| Paste as dbt Refs | `Cmd+Shift+V` | `Ctrl+Shift+V` |
| dbt Preview | `Cmd+Shift+R` | `Ctrl+Shift+R` |

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

---

## License

[MIT](LICENSE)

---

*This plugin is not affiliated with or endorsed by dbt Labs.*
