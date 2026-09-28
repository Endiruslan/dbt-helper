# Contributing to dbt Helper

Thanks for helping! Bug reports, fixes and features are all welcome. This page explains how to get a
pull request merged quickly. The short version: **show us the problem, show us that you tested the fix**.

## AI-assisted contributions

AI-assisted PRs are welcome. Please:

- **Say so** in the PR description.
- **Own the result.** You should understand every change in the diff and be able to explain why it is
  needed. "The AI wrote it" is not an answer to a review question.
- **Test it yourself** in a real IDE, as described below. Code that compiles is not code that works.

## What we won't merge

These PRs are closed without a detailed review:

- **Findings from an AI or static-analysis scan** with no real-world impact: "potential" bugs,
  "hardening" or "hygiene" that no user has run into.
- **Dependency bumps or library swaps** without a concrete bug, or a security advisory that actually
  affects this plugin.
- **Large rewrites, refactors or clean-ups** that weren't agreed on in an issue first.
- **Batches of unrelated changes**, or a series of near-identical PRs.
- **Changes you haven't run yourself.**

If you think you found a security vulnerability, don't open a public PR or issue. Report it privately
to the maintainer (contact on the [Marketplace page](https://plugins.jetbrains.com/plugin/31663-dbt-helper))
with a concrete scenario showing how it can be exploited.

## Before you open a PR

1. **Start from a real problem.** Link an issue, or describe a problem you actually ran into while
   using the plugin: what went wrong, how to reproduce it, and why it matters. For bigger changes (new
   features, new settings, refactors), open an issue first so we can agree on the approach before you
   spend time on it.
2. **One change per PR.** Unrelated fixes go in separate PRs. If two of your PRs touch the same files,
   say so, or base one on the other, so they don't conflict.
3. **Keep the diff small** and in the style of the surrounding code. No drive-by reformatting or renames.
4. **No new dependencies** without discussing it first.
5. **Don't bump the version** or edit `<change-notes>`. Suggest a changelog line in the PR description
   instead; it goes in at release time.

## Build and run locally

Requirements: **JDK 21** and a dbt project to test on (with `manifest.json`: run `dbt compile` or
`dbt docs generate` first).

```bash
./gradlew buildPlugin   # plugin ZIP in build/distributions/
./gradlew runIde        # sandbox IDE with the plugin installed
```

The sandbox is fine for quick checks, but please also install the ZIP into the IDE you actually use
(**Settings → Plugins → ⚙ → Install Plugin from Disk**) and restart it. Startup timing and indexing
behave differently in a real IDE, and some bugs only show up there.

## Testing checklist

There are no automated UI tests, so your manual testing is what we rely on. In the PR, list what you
tested and in which IDE and version. Add screenshots or a short GIF for anything visible.

- [ ] The scenario from the PR description works, and it did **not** work before your change.
- [ ] **Restart the IDE** and check again. Things that work after a settings change can still break on
      a cold start, while the project is still indexing.
- [ ] **Settings take effect without an IDE restart**: change the setting, press OK, check the Status,
      Lineage and Runner tabs.
- [ ] **Runner commands agree with the plugin.** If your change affects which files the plugin reads
      (manifest, catalog, profiles), `dbt run/compile/docs generate` started from the Runner must use the
      same files. The IDE does not see environment variables from your shell.
- [ ] **Large projects**: if your change touches completion, lineage or manifest parsing, try a project
      with a few hundred models, or say you couldn't.
- [ ] `./gradlew buildPlugin` passes **without new compiler warnings**.
- [ ] If you use new IntelliJ Platform APIs: `./gradlew verifyPlugin` passes. It downloads several IDEs
      and takes a while, so say in the PR if you skipped it.
- [ ] `idea.log` has no new exceptions from `com.dbthelper` (**Help → Show Log in Finder/Explorer**).

## Code notes

Things we have been bitten by before:

- **Threading.** Don't run index queries or slow I/O on the EDT (UI thread); IDE 2026.2+ reports them
  as errors. At startup, `DbtProjectLocator.findProjectRoot()` can return `null` on the EDT until the
  background scan finishes, so handle that instead of falling back to a guess.
- **Use `DbtProjectLocator`** for paths (project root, target dir, profiles). Don't hardcode `"target"`
  or `~/.dbt`.
- **Settings changes are frequent.** `SettingsChangeListener` also fires when the user picks a target
  in the Runner, so don't do expensive work (for example re-parsing the manifest) on every change. Check
  that the value you care about actually changed.
- **Files created by dbt** may not be in the IDE's virtual file system yet. Refresh before you conclude a
  file doesn't exist.
- **Comment the why**, not the what, the way the surrounding code does.

## Commits and PRs

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `fix: …`,
  `feat: …`, `docs: …`, `chore: …`.
- Keep **"Allow edits by maintainers"** checked, so we can push small fixes to your branch instead of
  asking for another round.
- We review and re-test every PR ourselves and may make follow-up changes before release. The more of
  the checklist you cover, the faster that goes.
